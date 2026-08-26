package com.slm.bridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TransferStore {
    static final class Item {
        final String id;
        final String filename;
        final File file;
        final String registration;
        final String driveSubfolder;
        final boolean uploadRequested;
        boolean downloadComplete;
        boolean uploaded;
        final boolean archiveRequested;
        boolean analysisComplete;
        boolean archived;
        String sha256;
        String integrityStatus;
        String uploadSession;
        long uploadedBytes;
        final long createdAt;

        Item(String id, String filename, File file, String registration, String driveSubfolder,
             boolean uploadRequested,
             boolean downloadComplete, boolean uploaded, boolean archiveRequested,
             boolean analysisComplete, boolean archived, String sha256, String integrityStatus,
             String uploadSession, long uploadedBytes, long createdAt) {
            this.id = id;
            this.filename = filename;
            this.file = file;
            this.registration = registration;
            this.driveSubfolder = normalizeDriveSubfolder(driveSubfolder);
            this.uploadRequested = uploadRequested;
            this.downloadComplete = downloadComplete;
            this.uploaded = uploaded;
            this.archiveRequested = archiveRequested;
            this.analysisComplete = analysisComplete;
            this.archived = archived;
            this.sha256 = sha256;
            this.integrityStatus = integrityStatus == null ? "" : integrityStatus;
            this.uploadSession = uploadSession;
            this.uploadedBytes = uploadedBytes;
            this.createdAt = createdAt;
        }

        String localUrl() { return "https://slm-app.local/transfers/" + id + "/content"; }
    }

    private final File directory;
    private final Map<String, Item> items = new LinkedHashMap<>();

    TransferStore(Context context) {
        directory = new File(context.getFilesDir(), "transfers");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create transfer directory");
        }
        loadExisting();
    }

    synchronized Item create(String filename, String registration, boolean uploadRequested) throws Exception {
        for (Item existing : items.values()) {
            if (existing.driveSubfolder.isEmpty()
                    && existing.registration.equals(registration)
                    && existing.filename.equals(filename)
                    && !existing.archived) {
                throw new IllegalStateException("File is already being processed");
            }
        }
        String id = UUID.randomUUID().toString();
        Item item = new Item(id, filename, dataFile(id), registration, "",
                uploadRequested, false, false, uploadRequested, false, false,
                "", "", "", 0, System.currentTimeMillis());
        items.put(id, item);
        persist(item);
        return item;
    }

    synchronized Item createReport(String filename, String registration) throws Exception {
        String id = UUID.randomUUID().toString();
        Item item = new Item(id, filename, dataFile(id), registration, "reports",
                true, false, false, false, true, false,
                "", "", "", 0, System.currentTimeMillis());
        items.put(id, item);
        persist(item);
        return item;
    }

    synchronized void markDownloaded(Item item, String sha256, String integrityStatus) throws Exception {
        item.downloadComplete = true;
        item.sha256 = sha256;
        item.integrityStatus = integrityStatus == null ? "" : integrityStatus;
        persist(item);
    }

    synchronized void updateSession(Item item, String session, long uploadedBytes) throws Exception {
        item.uploadSession = session == null ? "" : session;
        item.uploadedBytes = uploadedBytes;
        persist(item);
    }

    synchronized void markUploaded(Item item) throws Exception {
        item.uploaded = true;
        item.uploadSession = "";
        item.uploadedBytes = item.file.length();
        persist(item);
    }

    synchronized void markAnalysisComplete(Item item) throws Exception {
        item.analysisComplete = true;
        persist(item);
    }

    synchronized void markArchived(Item item) throws Exception {
        item.archived = true;
        persist(item);
    }

    synchronized Item get(String id) { return items.get(id); }

    synchronized List<Item> pendingUploads() {
        List<Item> result = new ArrayList<>();
        for (Item item : items.values()) {
            if (item.uploadRequested && item.downloadComplete && item.analysisComplete
                    && !item.uploaded && item.file.isFile()) {
                result.add(item);
            }
        }
        return result;
    }

    synchronized boolean hasPendingUploads() { return !pendingUploads().isEmpty(); }

    synchronized boolean hasActiveRecording(String registration, String filename) {
        for (Item item : items.values()) {
            if (!item.driveSubfolder.isEmpty() || item.archived) continue;
            if (item.registration.equals(registration) && item.filename.equals(filename)) return true;
        }
        return false;
    }

    synchronized List<Item> pendingArchives() {
        List<Item> result = new ArrayList<>();
        for (Item item : items.values()) {
            if (item.archiveRequested && item.uploaded && item.analysisComplete
                    && !item.archived && item.file.isFile()) {
                result.add(item);
            }
        }
        return result;
    }


    synchronized String recorderTransferStates(String registration) {
        JSONArray files = new JSONArray();
        try {
            for (Item item : items.values()) {
                if (!item.driveSubfolder.isEmpty() || item.archived
                        || !item.registration.equals(registration)) continue;
                String state;
                if (!item.downloadComplete) state = "downloading";
                else if (!item.analysisComplete) state = "analyzing";
                else if (!item.uploaded) state = "queued";
                else state = "finalizing";

                JSONObject file = new JSONObject();
                file.put("filename", item.filename);
                file.put("state", state);
                files.put(file);
            }

            JSONObject result = new JSONObject();
            result.put("files", files);
            return result.toString();
        } catch (Exception ignored) {
            return "{\"files\":[]}";
        }
    }

    synchronized void delete(String id) {
        Item item = items.remove(id);
        if (item == null) return;
        if (item.file.exists()) item.file.delete();
        File metadata = metadataFile(id);
        if (metadata.exists()) metadata.delete();
    }

    private void loadExisting() {
        File[] metadata = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (metadata == null) return;
        for (File file : metadata) {
            try {
                JSONObject value = new JSONObject(readText(file));
                String id = value.getString("id");
                Item item = new Item(id, value.getString("filename"), dataFile(id),
                        value.getString("registration"), value.optString("driveSubfolder"),
                        value.optBoolean("uploadRequested", true),
                        value.optBoolean("downloadComplete"), value.optBoolean("uploaded"),
                        value.optBoolean("archiveRequested", false),
                        value.optBoolean("analysisComplete", false),
                        value.optBoolean("archived", false),
                        value.optString("sha256"), value.optString("integrityStatus", "legacy"),
                        value.optString("uploadSession"), value.optLong("uploadedBytes"),
                        value.optLong("createdAt"));
                if (item.uploaded && !item.archiveRequested) {
                    removeFiles(id);
                } else if (item.downloadComplete && item.file.isFile()) {
                    items.put(id, item);
                } else {
                    removeFiles(id);
                }
            } catch (Exception ignored) {
                String name = file.getName();
                removeFiles(name.substring(0, name.length() - 5));
            }
        }
    }

    private void persist(Item item) throws Exception {
        JSONObject value = new JSONObject()
                .put("version", 3)
                .put("id", item.id)
                .put("filename", item.filename)
                .put("registration", item.registration)
                .put("driveSubfolder", item.driveSubfolder)
                .put("uploadRequested", item.uploadRequested)
                .put("downloadComplete", item.downloadComplete)
                .put("uploaded", item.uploaded)
                .put("archiveRequested", item.archiveRequested)
                .put("analysisComplete", item.analysisComplete)
                .put("archived", item.archived)
                .put("sha256", item.sha256)
                .put("integrityStatus", item.integrityStatus)
                .put("uploadSession", item.uploadSession)
                .put("uploadedBytes", item.uploadedBytes)
                .put("createdAt", item.createdAt);
        File target = metadataFile(item.id);
        File temporary = new File(directory, item.id + ".json.tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot update transfer metadata");
        if (!temporary.renameTo(target)) throw new IllegalStateException("Cannot save transfer metadata");
    }

    private File dataFile(String id) { return new File(directory, id + ".data"); }
    private File metadataFile(String id) { return new File(directory, id + ".json"); }

    private static String normalizeDriveSubfolder(String value) {
        return "reports".equals(value) ? "reports" : "";
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void removeFiles(String id) {
        File data = dataFile(id);
        File metadata = metadataFile(id);
        if (data.exists()) data.delete();
        if (metadata.exists()) metadata.delete();
    }
}
