package com.mbworldwideapps.aiorchestration.modules.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.config.PersonalMemoryProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The panel's Jobs tab: the last job handoff, saved jobs and personal memories, readable and deletable. */
@Service
public class JobsPanelService {

    private static final int LIMIT = 100;

    public record LastJob(String content, String updatedAt) {}

    public record Entry(String id, String summary, String content, String updatedAt) {}

    public record Overview(LastJob lastJob, List<Entry> jobs, List<Entry> personal) {}

    private final JdbcTemplate jdbc;
    private final ObjectProvider<QdrantClient> qdrant;
    private final JobMemoryProperties jobs;
    private final PersonalMemoryProperties personal;

    public JobsPanelService(JdbcTemplate jdbc, ObjectProvider<QdrantClient> qdrant, JobMemoryProperties jobs,
            PersonalMemoryProperties personal) {
        this.jdbc = jdbc;
        this.qdrant = qdrant;
        this.jobs = jobs;
        this.personal = personal;
    }

    public Overview overview() {
        LastJob last = jdbc.query("SELECT content, updated_at FROM last_job WHERE singleton_id = 1",
                rs -> rs.next() ? new LastJob(rs.getString(1), rs.getTimestamp(2).toInstant().toString()) : null);
        return new Overview(last, list(jobs.collectionName(), false), list(personal.collectionName(), true));
    }

    public Entry get(String kind, String id) {
        String collection = collection(kind);
        try {
            var points = client().retrieveAsync(collection, List.of(pointId(id)), true, false, null)
                    .get(8, TimeUnit.SECONDS);
            if (points.isEmpty()) throw new NoSuchElementException("Kayıt bulunamadı");
            return entry(id, points.getFirst().getPayloadMap(), "personal".equals(kind), true);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Yerel kayıt kaynağına erişilemiyor", e);
        }
    }

    public Map<String, Object> delete(String kind, String id) {
        String collection = collection(kind);
        try {
            client().deleteAsync(collection, List.of(pointId(id))).get(8, TimeUnit.SECONDS);
            return Map.of("deleted", true, "id", id);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Kayıt silinemedi", e);
        }
    }

    private List<Entry> list(String collection, boolean personalEntries) {
        try {
            var response = client().scrollAsync(Points.ScrollPoints.newBuilder()
                    .setCollectionName(collection).setLimit(LIMIT)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true)).build())
                    .get(8, TimeUnit.SECONDS);
            List<Entry> out = new ArrayList<>();
            for (var point : response.getResultList()) {
                out.add(entry(point.getId().getUuid(), point.getPayloadMap(), personalEntries, false));
            }
            out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
            return out;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of(); // collection not created yet (nothing saved) or Qdrant down: an empty list
        }
    }

    private static Entry entry(String id, Map<String, JsonWithInt.Value> payload, boolean personalEntry,
            boolean withContent) {
        var fields = payload.containsKey("metadata") ? payload.get("metadata").getStructValue().getFieldsMap()
                : payload;
        String content = personalEntry ? text(payload, "doc_content")
                : !text(fields, "content").isEmpty() ? text(fields, "content") : text(payload, "doc_content");
        String summary = personalEntry ? content
                : !text(fields, "title").isEmpty() ? text(fields, "title") : text(fields, "summary");
        if (summary.length() > 160) summary = summary.substring(0, 160) + "…";
        String updated = !text(fields, "updatedAt").isEmpty() ? text(fields, "updatedAt") : text(fields, "savedAt");
        return new Entry(id, summary, withContent ? content : null, updated);
    }

    private static String text(Map<String, JsonWithInt.Value> fields, String key) {
        return fields.containsKey(key) ? fields.get(key).getStringValue() : "";
    }

    private String collection(String kind) {
        return switch (kind) {
            case "job" -> jobs.collectionName();
            case "personal" -> personal.collectionName();
            default -> throw new IllegalArgumentException(PanelText.t("Kind must be job or personal",
                    "Tür job veya personal olmalı"));
        };
    }

    private static Points.PointId pointId(String id) {
        return Points.PointId.newBuilder().setUuid(UUID.fromString(id).toString()).build();
    }

    private QdrantClient client() {
        QdrantClient client = qdrant.getIfAvailable();
        if (client == null) throw new IllegalStateException("Qdrant yapılandırılmamış");
        return client;
    }
}
