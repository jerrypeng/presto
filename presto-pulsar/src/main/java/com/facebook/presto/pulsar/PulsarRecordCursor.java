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
package com.facebook.presto.pulsar;

import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.VarbinaryType;
import com.facebook.presto.spi.type.VarcharType;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerFactory;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.ReadOnlyCursor;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerFactoryImpl;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.impl.MessageParser;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.shade.org.apache.bookkeeper.conf.ClientConfiguration;

import java.io.IOException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.SmallintType.SMALLINT;
import static com.facebook.presto.spi.type.TimeType.TIME;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.TinyintType.TINYINT;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class PulsarRecordCursor implements RecordCursor {

    private final List<PulsarColumnHandle> columnHandles;
    private final PulsarSplit pulsarSplit;
    private final PulsarConnectorConfig pulsarConnectorConfig;
    private ManagedLedgerFactory managedLedgerFactory;
    private ReadOnlyCursor cursor;
    private Queue<Message> messageQueue = new LinkedList<>();
    private Schema schema;
    private GenericRecord currentRecord;
    private Message currentMessage;
    private Map<String, PulsarInternalColumn> internalColumnMap = PulsarInternalColumn.getInternalFieldsMap();
    private Map<String, String> fieldToColumn = new HashMap<>();
    private static final int NUM_ENTRY_READ_BATCH = 100;


    private static final Logger log = Logger.get(PulsarRecordCursor.class);

    public PulsarRecordCursor(List<PulsarColumnHandle> columnHandles, PulsarSplit pulsarSplit, PulsarConnectorConfig
            pulsarConnectorConfig) {
        this.columnHandles = columnHandles;
        this.pulsarSplit = pulsarSplit;
        this.pulsarConnectorConfig = pulsarConnectorConfig;

        this.schema = PulsarConnectorUtils.parseSchema(this.pulsarSplit.getSchema());

        log.info("Start: %s end: %s", this.pulsarSplit.getStartPosition(), this.pulsarSplit.getEndPosition());

        try {
             this.cursor = getCursor(TopicName.get("persistent", NamespaceName.get(this.pulsarSplit.getSchemaName()),
                     this.pulsarSplit.getTableName()), this.pulsarSplit.getStartPosition());
        } catch (Exception e) {
            log.error(e, "Failed to get cursor");
            close();
            throw new RuntimeException(e);
        }
    }

    private ReadOnlyCursor getCursor(TopicName topicName, Position startPosition) throws Exception {
        ClientConfiguration bkClientConfiguration = new ClientConfiguration()
                .setZkServers(this.pulsarConnectorConfig.getZookeeperUri())
                .setAllowShadedLedgerManagerFactoryClass(true)
                .setShadedLedgerManagerFactoryClassPrefix("org.apache.pulsar.shade.");

        managedLedgerFactory = new ManagedLedgerFactoryImpl(bkClientConfiguration);

        ManagedLedgerConfig managedLedgerConfig = new ManagedLedgerConfig().setEnsembleSize(1).setWriteQuorumSize(1).setAckQuorumSize(1).setMetadataEnsembleSize(1).setMetadataWriteQuorumSize(1).setMetadataAckQuorumSize(1);

        ReadOnlyCursor cursor = managedLedgerFactory.openReadOnlyCursor(topicName.getPersistenceNamingEncoding(),
                startPosition, managedLedgerConfig);

        return cursor;
    }

    @Override
    public long getCompletedBytes() {
        return 0;
    }

    @Override
    public long getReadTimeNanos() {
        return 0;
    }

    @Override
    public Type getType(int field) {
        checkArgument(field < columnHandles.size(), "Invalid field index");
        return columnHandles.get(field).getType();
    }

    @Override
    public boolean advanceNextPosition() {

        if (this.messageQueue.isEmpty()) {
            if (!this.cursor.hasMoreEntries()) {
                return false;
            }
            if (((PositionImpl) this.cursor.getReadPosition())
                    .compareTo(this.pulsarSplit.getEndPosition()) >= 0) {
                return false;
            }

            TopicName topicName = TopicName.get("persistent",
                    NamespaceName.get(this.pulsarSplit.getSchemaName()),
                    this.pulsarSplit.getTableName());

            List<Entry> newEntries;
            try {
                newEntries = this.cursor.readEntries(NUM_ENTRY_READ_BATCH);
            } catch (InterruptedException | ManagedLedgerException e) {
                log.error(e, "Failed to read new entries from pulsar topic %s", topicName.toString());
                throw new RuntimeException(e);
            }

            newEntries.forEach(new Consumer<Entry>() {
                @Override
                public void accept(Entry entry) {
                    // filter entries that is not part of my split
                    if (((PositionImpl) entry.getPosition()).compareTo(pulsarSplit.getEndPosition()) < 0) {
                        try {
                            MessageParser.parseMessage(topicName, entry.getLedgerId(), entry.getEntryId(),
                                    entry.getDataBuffer(), (messageId, message, byteBuf) -> {
                                        messageQueue.add(message);
                                    });
                        } catch (IOException e) {
                            log.error(e, "Failed to parse message from pulsar topic %s", topicName.toString());
                            throw new RuntimeException(e);
                        }
                    } else {
                        entry.release();
                    }
                }
            });
        }

        this.currentMessage = this.messageQueue.poll();

        DatumReader<GenericRecord> datumReader = new GenericDatumReader<>(this.schema);
        try {
            currentRecord = datumReader.read(null, DecoderFactory.get().binaryDecoder(this.currentMessage.getData(), null));
        } catch (IOException e) {
            log.error(e);
        }

        return true;
    }

    private Object getRecord(int fieldIndex) {

        Object data;
        PulsarColumnHandle pulsarColumnHandle = this.columnHandles.get(fieldIndex);

        if (pulsarColumnHandle.isInternal()) {
            String fieldName = this.columnHandles.get(fieldIndex).getName();
            PulsarInternalColumn pulsarInternalColumn = this.internalColumnMap.get(fieldName);
            data = pulsarInternalColumn.getData(this.currentMessage);
        } else {
            data = this.currentRecord.get(this.columnHandles.get(fieldIndex).getPositionIndex());
        }

        return data;
    }

    @Override
    public boolean getBoolean(int field) {
        checkFieldType(field, boolean.class);
        return (boolean) getRecord(field);
    }

    @Override
    public long getLong(int field) {
        checkFieldType(field, long.class);

        Object record = getRecord(field);
        Type type = getType(field);

        if (type.equals(BIGINT)) {
            return (long) record;
        }
        else if (type.equals(DATE)) {
            return MILLISECONDS.toDays(new Date(TimeUnit.DAYS.toMillis((long) record)).getTime());
        }
        else if (type.equals(INTEGER)) {
            return (int) record;
        }
        else if (type.equals(REAL)) {
            return Float.floatToIntBits((float) record);
        }
        else if (type.equals(SMALLINT)) {
            return (short) record;
        }
        else if (type.equals(TIME)) {
            return new Time((long) record).getTime();
        }
        else if (type.equals(TIMESTAMP)) {
            return new Timestamp((long) record).getTime();
        }
        else if (type.equals(TINYINT)) {
            return Byte.parseByte(record.toString());
        }
        else {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported type " + getType(field));
        }
    }

    @Override
    public double getDouble(int field) {
        checkFieldType(field, double.class);
        Object record = getRecord(field);
        return (double) record;
    }

    @Override
    public Slice getSlice(int field) {
        checkFieldType(field, Slice.class);

        Object record = getRecord(field);
        Type type = getType(field);
        if (type == VarcharType.VARCHAR) {
            return Slices.utf8Slice(record.toString());
        } else if (type == VarbinaryType.VARBINARY) {
            return  Slices.wrappedBuffer((byte[]) record);
        } else {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported type " + type);
        }
    }

    @Override
    public Object getObject(int field) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isNull(int field) {
        Object record = getRecord(field);
        return record == null;
    }

    @Override
    public void close() {

        if (this.cursor != null) {
            try {
                this.cursor.close();
            } catch (Exception e) {
                log.error(e);
            }
        }

        if (managedLedgerFactory != null) {
            try {
                managedLedgerFactory.shutdown();
            } catch (Exception e) {
                log.error(e);
            }
        }
    }

    private void checkFieldType(int field, Class<?> expected)
    {
        Class<?> actual = getType(field).getJavaType();
        checkArgument(actual == expected, "Expected field %s to be type %s but is %s", field, expected, actual);
    }
}
