package com.mbworldwideapps.aiorchestration.core.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

public class HashingEmbeddingModel implements EmbeddingModel {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final int dimensions;

    public HashingEmbeddingModel(int dimensions) {
        if (dimensions < 32) {
            throw new IllegalArgumentException("Embedding dimensions must be >= 32");
        }
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;
        for (String instruction : request.getInstructions()) {
            embeddings.add(new Embedding(embed(instruction), index++));
        }
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata("hashing-" + dimensions, null));
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        String normalized = normalize(text);
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            addToken(vector, token, 1.0f);
            for (String ngram : charNgrams(token)) {
                addToken(vector, ngram, 0.35f);
            }
        }
        normalize(vector);
        return vector;
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private static String normalize(String text) {
        String value = text == null ? "" : text;
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static List<String> charNgrams(String token) {
        List<String> grams = new ArrayList<>();
        if (token.length() < 4) {
            grams.add(token);
            return grams;
        }
        for (int i = 0; i <= token.length() - 4; i++) {
            grams.add(token.substring(i, i + 4));
        }
        return grams;
    }

    private void addToken(float[] vector, String token, float weight) {
        byte[] digest = sha256(token);
        int bucket = Math.floorMod(toInt(digest, 0), dimensions);
        int sign = (digest[4] & 1) == 0 ? 1 : -1;
        vector[bucket] += sign * weight;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static int toInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static void normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
