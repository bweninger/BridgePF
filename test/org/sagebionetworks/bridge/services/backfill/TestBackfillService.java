package org.sagebionetworks.bridge.services.backfill;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.sagebionetworks.bridge.models.BackfillRecord;
import org.sagebionetworks.bridge.models.BackfillTask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class TestBackfillService extends AsyncBackfillTemplate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String RECORD_FIELD = "record";
    static final String RECORD_1 = "1";
    static final String RECORD_2 = "2";
    static final String RECORD_3 = "3";
    static final String OPERATION_FIELD = "operation";
    static final String OPERATION_1 = "created";
    static final String OPERATION_2 = "recreated";
    static final String OPERATION_3 = "deleted";
    static final int EXPIRE = 60;

    @Override
    int getLockExpireInSeconds() {
        return EXPIRE;
    }

    @Override
    void doBackfill(final BackfillTask task, final BackfillCallback callback) {

        callback.newRecords(new BackfillRecord() {
            @Override
            public String getTaskId() {
                return task.getId();
            }
            @Override
            public long getTimestamp() {
                return DateTime.now(DateTimeZone.UTC).getMillis();
            }
            @Override
            public String getRecord() {
                ObjectNode node = MAPPER.createObjectNode();
                node.put(RECORD_FIELD, RECORD_1);
                node.put(OPERATION_FIELD, OPERATION_1);
                try {
                    return MAPPER.writeValueAsString(node);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        BackfillRecord record2 = new BackfillRecord() {
            @Override
            public String getTaskId() {
                return task.getId();
            }
            @Override
            public long getTimestamp() {
                return DateTime.now(DateTimeZone.UTC).getMillis();
            }
            @Override
            public String getRecord() {
                ObjectNode node = MAPPER.createObjectNode();
                node.put(RECORD_FIELD, RECORD_2);
                node.put(OPERATION_FIELD, OPERATION_2);
                try {
                    return MAPPER.writeValueAsString(node);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        BackfillRecord record3 = new BackfillRecord() {
            @Override
            public String getTaskId() {
                return task.getId();
            }
            @Override
            public long getTimestamp() {
                return DateTime.now(DateTimeZone.UTC).getMillis();
            }
            @Override
            public String getRecord() {
                ObjectNode node = MAPPER.createObjectNode();
                node.put(RECORD_FIELD, RECORD_3);
                node.put(OPERATION_FIELD, OPERATION_3);
                try {
                    return MAPPER.writeValueAsString(node);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        callback.newRecords(record2, record3);
    }
}
