// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.android.AndroidResourceMerger.MergingException;
import com.google.devtools.build.android.ParsedAndroidData.KeyValueConsumer;
import com.google.devtools.build.android.proto.SerializeFormat;
import com.google.devtools.build.android.proto.SerializeFormat.Header;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Deserializes {@link DataKey}, {@link DataValue} entries from a binary file. */
public class AndroidParsedDataDeserializer implements AndroidDataDeserializer {
  /** Task to deserialize resources from a path. */
  static final class Deserialize implements Callable<Boolean> {

    private final Path symbolPath;

    private final ParsedAndroidData.Builder finalDataBuilder;
    private final AndroidParsedDataDeserializer deserializer;

    private Deserialize(
        AndroidParsedDataDeserializer deserializer,
        Path symbolPath,
        ParsedAndroidData.Builder finalDataBuilder) {
      this.deserializer = deserializer;
      this.symbolPath = symbolPath;
      this.finalDataBuilder = finalDataBuilder;
    }

    @Override
    public Boolean call() throws Exception {
      final ParsedAndroidData.Builder parsedDataBuilder = ParsedAndroidData.Builder.newBuilder();
      deserializer.read(symbolPath, parsedDataBuilder.consumers());
      // The builder isn't threadsafe, so synchronize the copyTo call.
      synchronized (finalDataBuilder) {
        // All the resources are sorted before writing, so they can be aggregated in
        // whatever order here.
        parsedDataBuilder.copyTo(finalDataBuilder);
      }
      return Boolean.TRUE;
    }
  }

  private static final Logger logger =
      Logger.getLogger(AndroidParsedDataDeserializer.class.getName());

  private final ImmutableSet<String> filteredResources;

  /**
   * @param filteredResources resources that were filtered out of this target and should be ignored
   *     if they are referenced in symbols files.
   */
  public static AndroidParsedDataDeserializer withFilteredResources(
      Collection<String> filteredResources) {
    return new AndroidParsedDataDeserializer(ImmutableSet.copyOf(filteredResources));
  }

  public static AndroidParsedDataDeserializer create() {
    return new AndroidParsedDataDeserializer(ImmutableSet.<String>of());
  }

  private AndroidParsedDataDeserializer(ImmutableSet<String> filteredResources) {
    this.filteredResources = filteredResources;
  }

  /**
   * Reads the serialized {@link DataKey} and {@link DataValue} to the {@link KeyValueConsumers}.
   *
   * @param inPath The path to the serialized protocol buffer.
   * @param consumers The {@link KeyValueConsumers} for the entries {@link DataKey} -&gt; {@link
   *     DataValue}.
   * @throws DeserializationException Raised for an IOException or when the inPath is not a valid
   *     proto buffer.
   */
  @Override
  public void read(Path inPath, KeyValueConsumers consumers) {
    Stopwatch timer = Stopwatch.createStarted();
    try (InputStream in = Files.newInputStream(inPath, StandardOpenOption.READ)) {
      FileSystem currentFileSystem = inPath.getFileSystem();
      Header header = Header.parseDelimitedFrom(in);
      if (header == null) {
        throw new DeserializationException("No Header found in " + inPath);
      }
      readEntriesSegment(consumers, in, currentFileSystem, header);
    } catch (IOException e) {
      throw new DeserializationException("Error deserializing " + inPath, e);
    } finally {
      logger.fine(
          String.format("Deserialized in merged in %sms", timer.elapsed(TimeUnit.MILLISECONDS)));
    }
  }

  private void readEntriesSegment(
      KeyValueConsumers consumers, InputStream in, FileSystem currentFileSystem, Header header)
      throws IOException {
    int numberOfEntries = header.getEntryCount();
    Map<DataKey, KeyValueConsumer<DataKey, ? extends DataValue>> keys =
        Maps.newLinkedHashMapWithExpectedSize(numberOfEntries);
    for (int i = 0; i < numberOfEntries; i++) {
      SerializeFormat.DataKey protoKey = SerializeFormat.DataKey.parseDelimitedFrom(in);
      if (protoKey.hasResourceType()) {
        FullyQualifiedName resourceName = FullyQualifiedName.fromProto(protoKey);
        keys.put(
            resourceName,
            resourceName.isOverwritable()
                ? consumers.overwritingConsumer
                : consumers.combiningConsumer);
      } else {
        keys.put(RelativeAssetPath.fromProto(protoKey, currentFileSystem), consumers.assetConsumer);
      }
    }

    // Read back the sources table.
    DataSourceTable sourceTable = DataSourceTable.read(in, currentFileSystem, header);

    // TODO(corysmith): Make this a lazy read of the values.
    for (Map.Entry<DataKey, KeyValueConsumer<DataKey, ?>> entry : keys.entrySet()) {
      SerializeFormat.DataValue protoValue = SerializeFormat.DataValue.parseDelimitedFrom(in);
      DataSource source = sourceTable.sourceFromId(protoValue.getSourceId());
      // Compose the `shortPath` manually to ensure it uses a forward slash.
      // Using Path.subpath would return a backslash-using path on Windows.
      String shortPath =
          source.getPath().getParent().getFileName() + "/" + source.getPath().getFileName();
      if (filteredResources.contains(shortPath) && !Files.exists(source.getPath())) {
        // Skip files that were filtered out during analysis.
        // TODO(asteinb): Properly filter out these files from android_library symbol files during
        // analysis instead, and remove this list.
        continue;
      }
      if (protoValue.hasXmlValue()) {
        // TODO(corysmith): Figure out why the generics are wrong.
        // If I use Map<DataKey, KeyValueConsumer<DataKey, ? extends DataValue>>, I can put
        // consumers into the map, but I can't call accept.
        // If I use Map<DataKey, KeyValueConsumer<DataKey, ? super DataValue>>, I can consume
        // but I can't put.
        // Same for below.
        @SuppressWarnings("unchecked")
        KeyValueConsumer<DataKey, DataValue> value =
            (KeyValueConsumer<DataKey, DataValue>) entry.getValue();
        value.accept(entry.getKey(), DataResourceXml.from(protoValue, source));
      } else {
        @SuppressWarnings("unchecked")
        KeyValueConsumer<DataKey, DataValue> value =
            (KeyValueConsumer<DataKey, DataValue>) entry.getValue();
        value.accept(entry.getKey(), DataValueFile.of(source));
      }
    }
  }

  /**
   * Deserializes a list of serialized resource paths to a {@link
   * com.google.devtools.build.android.ParsedAndroidData}.
   */
  // NB: this is not just visible for testing, it's *only* used in a test.
  @VisibleForTesting
  static ParsedAndroidData deserializeSymbolsToData(List<Path> symbolPaths) throws IOException {
    AndroidParsedDataDeserializer deserializer = create();
    final ListeningExecutorService executorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(15));
    final ParsedAndroidData.Builder deserializedDataBuilder =
        ParsedAndroidData.Builder.newBuilder();
    try (Closeable closeable = ExecutorServiceCloser.createWith(executorService)) {
      List<ListenableFuture<Boolean>> deserializing = new ArrayList<>();
      for (final Path symbolPath : symbolPaths) {
        deserializing.add(
            executorService.submit(
                new AndroidParsedDataDeserializer.Deserialize(
                    deserializer, symbolPath, deserializedDataBuilder)));
      }
      FailedFutureAggregator<MergingException> aggregator =
          FailedFutureAggregator.createForMergingExceptionWithMessage(
              "Failure(s) during dependency parsing");
      aggregator.aggregateAndMaybeThrow(deserializing);
    }
    return deserializedDataBuilder.build();
  }
}
