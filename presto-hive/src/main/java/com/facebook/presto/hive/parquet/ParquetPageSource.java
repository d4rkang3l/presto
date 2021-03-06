/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive.parquet;

import com.facebook.presto.hive.HiveColumnHandle;
import com.facebook.presto.hive.parquet.memory.AggregatedMemoryContext;
import com.facebook.presto.hive.parquet.reader.ParquetReader;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.LazyBlock;
import com.facebook.presto.spi.block.LazyBlockLoader;
import com.facebook.presto.spi.block.RunLengthEncodedBlock;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import parquet.column.ColumnDescriptor;
import parquet.schema.MessageType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static com.facebook.presto.hive.HiveColumnHandle.ColumnType.REGULAR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static com.facebook.presto.hive.parquet.ParquetTypeUtils.getDescriptor;
import static com.facebook.presto.hive.parquet.ParquetTypeUtils.getFieldIndex;
import static com.facebook.presto.hive.parquet.ParquetTypeUtils.getParquetType;
import static com.facebook.presto.spi.type.StandardTypes.ARRAY;
import static com.facebook.presto.spi.type.StandardTypes.MAP;
import static com.facebook.presto.spi.type.StandardTypes.ROW;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class ParquetPageSource
        implements ConnectorPageSource
{
    private static final int MAX_VECTOR_LENGTH = 1024;

    private final ParquetReader parquetReader;
    private final ParquetDataSource dataSource;
    private final MessageType fileSchema;
    // for debugging heap dump
    private final MessageType requestedSchema;
    private final List<String> columnNames;
    private final List<Type> types;

    private final Block[] constantBlocks;
    private final int[] hiveColumnIndexes;

    private int batchId;
    private boolean closed;
    private long readTimeNanos;
    private final boolean useParquetColumnNames;

    private final AggregatedMemoryContext systemMemoryContext;

    public ParquetPageSource(
            ParquetReader parquetReader,
            ParquetDataSource dataSource,
            MessageType fileSchema,
            MessageType requestedSchema,
            Properties splitSchema,
            List<HiveColumnHandle> columns,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            TypeManager typeManager,
            boolean useParquetColumnNames,
            AggregatedMemoryContext systemMemoryContext)
    {
        requireNonNull(splitSchema, "splitSchema is null");
        requireNonNull(columns, "columns is null");
        requireNonNull(effectivePredicate, "effectivePredicate is null");

        this.parquetReader = requireNonNull(parquetReader, "parquetReader is null");
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
        this.fileSchema = requireNonNull(fileSchema, "fileSchema is null");
        this.requestedSchema = requireNonNull(requestedSchema, "requestedSchema is null");
        this.useParquetColumnNames = useParquetColumnNames;

        int size = columns.size();
        this.constantBlocks = new Block[size];
        this.hiveColumnIndexes = new int[size];

        ImmutableList.Builder<String> namesBuilder = ImmutableList.builder();
        ImmutableList.Builder<Type> typesBuilder = ImmutableList.builder();
        for (int columnIndex = 0; columnIndex < size; columnIndex++) {
            HiveColumnHandle column = columns.get(columnIndex);
            checkState(column.getColumnType() == REGULAR, "column type must be regular");

            String name = column.getName();
            Type type = typeManager.getType(column.getTypeSignature());

            namesBuilder.add(name);
            typesBuilder.add(type);

            hiveColumnIndexes[columnIndex] = column.getHiveColumnIndex();

            if (getParquetType(column, fileSchema, useParquetColumnNames) == null) {
                constantBlocks[columnIndex] = RunLengthEncodedBlock.create(type, null, MAX_VECTOR_LENGTH);
            }
        }
        types = typesBuilder.build();
        columnNames = namesBuilder.build();
        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
    }

    @Override
    public long getCompletedBytes()
    {
        return dataSource.getReadBytes();
    }

    @Override
    public long getReadTimeNanos()
    {
        return readTimeNanos;
    }

    @Override
    public boolean isFinished()
    {
        return closed;
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return systemMemoryContext.getBytes();
    }

    @Override
    public Page getNextPage()
    {
        try {
            batchId++;
            long start = System.nanoTime();

            int batchSize = parquetReader.nextBatch();

            readTimeNanos += System.nanoTime() - start;

            if (closed || batchSize <= 0) {
                close();
                return null;
            }

            Block[] blocks = new Block[hiveColumnIndexes.length];
            for (int fieldId = 0; fieldId < blocks.length; fieldId++) {
                if (constantBlocks[fieldId] != null) {
                    blocks[fieldId] = constantBlocks[fieldId].getRegion(0, batchSize);
                }
                else {
                    Type type = types.get(fieldId);
                    int fieldIndex;
                    if (useParquetColumnNames) {
                        fieldIndex = getFieldIndex(fileSchema, columnNames.get(fieldId));
                    }
                    else {
                        fieldIndex = hiveColumnIndexes[fieldId];
                    }

                    if (fieldIndex == -1) {
                        blocks[fieldId] = RunLengthEncodedBlock.create(type, null, batchSize);
                        continue;
                    }

                    String fieldName = fileSchema.getFields().get(fieldIndex).getName();
                    List<String> path = new ArrayList<>();
                    path.add(fieldName);
                    if (ROW.equals(type.getTypeSignature().getBase())) {
                        blocks[fieldId] = parquetReader.readStruct(type, path);
                    }
                    else if (MAP.equals(type.getTypeSignature().getBase())) {
                        blocks[fieldId] = parquetReader.readMap(type, path);
                    }
                    else if (ARRAY.equals(type.getTypeSignature().getBase())) {
                        blocks[fieldId] = parquetReader.readArray(type, path);
                    }
                    else {
                        Optional<RichColumnDescriptor> descriptor = getDescriptor(fileSchema, requestedSchema, path);
                        if (descriptor.isPresent()) {
                            blocks[fieldId] = new LazyBlock(batchSize, new ParquetBlockLoader(descriptor.get(), type));
                        }
                        else {
                            blocks[fieldId] = RunLengthEncodedBlock.create(type, null, batchSize);
                        }
                    }
                }
            }
            return new Page(batchSize, blocks);
        }
        catch (PrestoException e) {
            closeWithSuppression(e);
            throw e;
        }
        catch (IOException | RuntimeException e) {
            closeWithSuppression(e);
            throw new PrestoException(HIVE_CURSOR_ERROR, e);
        }
    }

    private void closeWithSuppression(Throwable throwable)
    {
        requireNonNull(throwable, "throwable is null");
        try {
            close();
        }
        catch (RuntimeException e) {
            // Self-suppression not permitted
            if (e != throwable) {
                throwable.addSuppressed(e);
            }
        }
    }

    @Override
    public void close()
    {
        if (closed) {
            return;
        }
        closed = true;

        try {
            parquetReader.close();
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    private final class ParquetBlockLoader
            implements LazyBlockLoader<LazyBlock>
    {
        private final int expectedBatchId = batchId;
        private final ColumnDescriptor columnDescriptor;
        private final Type type;
        private boolean loaded;

        public ParquetBlockLoader(ColumnDescriptor columnDescriptor, Type type)
        {
            this.columnDescriptor = columnDescriptor;
            this.type = requireNonNull(type, "type is null");
        }

        @Override
        public final void load(LazyBlock lazyBlock)
        {
            if (loaded) {
                return;
            }

            checkState(batchId == expectedBatchId);

            try {
                Block block = parquetReader.readPrimitive(columnDescriptor, type);
                lazyBlock.setBlock(block);
            }
            catch (IOException e) {
                throw new PrestoException(HIVE_CURSOR_ERROR, e);
            }
            loaded = true;
        }
    }
}
