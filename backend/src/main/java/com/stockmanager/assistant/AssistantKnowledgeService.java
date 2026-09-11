package com.stockmanager.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.common.exception.BusinessException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 用户私有知识库：校验上传、提取切片、持久化百炼向量，并执行小规模混合检索。 */
@Service
public class AssistantKnowledgeService {
    private static final long MAX_FILE_BYTES = 6L * 1024 * 1024;
    private static final int MAX_EXTRACTED_CHARS = 1_000_000;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 1_000;
    private static final int MAX_RETRIEVAL_CHUNKS = 5_000;
    private static final Set<String> EXTENSIONS = Set.of(".pdf", ".docx", ".md", ".txt");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AssistantEmbeddingGateway embeddingGateway;
    private final Path storageRoot;

    public AssistantKnowledgeService(JdbcTemplate jdbc, ObjectMapper mapper,
                                     AssistantEmbeddingGateway embeddingGateway,
                                     @Value("${app.assistant.knowledge.storage-root:../runtime/knowledge}") String storageRoot) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.embeddingGateway = embeddingGateway;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public record DocumentView(String id, String name, String mediaType, long sizeBytes, int extractedChars,
                               int chunkCount, String status, String errorMessage, String embeddingModel,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Evidence(String citation, String documentId, String documentName, int chunkIndex,
                           String content, double score) {}
    public record SearchResult(String embeddingModel, List<Evidence> evidence) {}

    public List<DocumentView> list(long userId) {
        return jdbc.query("""
                SELECT id,original_name,media_type,size_bytes,extracted_chars,chunk_count,status,
                       error_message,embedding_model,created_at,updated_at
                FROM ai_knowledge_document WHERE user_id=? ORDER BY updated_at DESC,id DESC
                """, (rs, row) -> new DocumentView(rs.getString("id"), rs.getString("original_name"),
                rs.getString("media_type"), rs.getLong("size_bytes"), rs.getInt("extracted_chars"),
                rs.getInt("chunk_count"), rs.getString("status"), rs.getString("error_message"),
                rs.getString("embedding_model"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()), userId);
    }

    @Transactional
    public DocumentView upload(long userId, MultipartFile file, String traceId) {
        String originalName = file.getOriginalFilename() == null ? "" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String extension = extension(originalName);
        if (file.isEmpty()) throw badRequest("请选择非空文档");
        if (file.getSize() > MAX_FILE_BYTES) throw badRequest("单个知识库文档不能超过6MB");
        if (!EXTENSIONS.contains(extension)) throw badRequest("仅支持 PDF、DOCX、MD 和 TXT 文档");
        try {
            byte[] bytes = file.getBytes();
            String sha = sha256(bytes);
            List<DocumentView> duplicate = jdbc.query("""
                    SELECT id,original_name,media_type,size_bytes,extracted_chars,chunk_count,status,
                           error_message,embedding_model,created_at,updated_at
                    FROM ai_knowledge_document WHERE user_id=? AND sha256=?
                    """, (rs, row) -> new DocumentView(rs.getString("id"), rs.getString("original_name"),
                    rs.getString("media_type"), rs.getLong("size_bytes"), rs.getInt("extracted_chars"),
                    rs.getInt("chunk_count"), rs.getString("status"), rs.getString("error_message"),
                    rs.getString("embedding_model"), rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime()), userId, sha);
            if (!duplicate.isEmpty()) return duplicate.getFirst();

            String text = extract(bytes, originalName);
            List<String> chunks = chunks(text);
            if (chunks.isEmpty()) throw badRequest("文档中没有可检索的正文文本");
            if (chunks.size() > MAX_CHUNKS_PER_DOCUMENT) throw badRequest("文档切片过多，请拆分后上传");

            List<List<Double>> vectors = new ArrayList<>();
            String model = null;
            int dimensions = 0;
            for (int start = 0; start < chunks.size(); start += 10) {
                AssistantEmbeddingGateway.EmbeddingBatch batch = embeddingGateway.embed(
                        chunks.subList(start, Math.min(start + 10, chunks.size())), traceId);
                if (model == null) { model = batch.model(); dimensions = batch.dimensions(); }
                if (dimensions != batch.dimensions())
                    throw new QwenAssistantModelGateway.AssistantGatewayException("知识库向量维度在批次间不一致");
                vectors.addAll(batch.vectors());
            }

            String documentId = UUID.randomUUID().toString();
            Path documentDirectory = storageRoot.resolve(Long.toString(userId)).resolve(documentId).normalize();
            if (!documentDirectory.startsWith(storageRoot)) throw new IllegalStateException("invalid storage path");
            Files.createDirectories(documentDirectory);
            Path stored = documentDirectory.resolve("source" + extension);
            Files.write(stored, bytes);
            String relativePath = storageRoot.relativize(stored).toString().replace('\\', '/');
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("""
                    INSERT INTO ai_knowledge_document
                      (id,user_id,original_name,media_type,size_bytes,sha256,relative_path,extracted_chars,
                       chunk_count,status,embedding_model,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,'READY',?,?,?)
                    """, documentId, userId, originalName, mediaType(file, extension), bytes.length, sha,
                    relativePath, text.length(), chunks.size(), model, Timestamp.valueOf(now), Timestamp.valueOf(now));
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                jdbc.update("""
                        INSERT INTO ai_knowledge_chunk
                          (id,document_id,user_id,chunk_index,content,content_sha256,embedding,embedding_dimension,created_at)
                        VALUES(?,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), documentId, userId, index, chunk,
                        sha256(chunk.getBytes(StandardCharsets.UTF_8)), mapper.writeValueAsString(vectors.get(index)),
                        dimensions, Timestamp.valueOf(now));
            }
            return new DocumentView(documentId, originalName, mediaType(file, extension), bytes.length,
                    text.length(), chunks.size(), "READY", null, model, now, now);
        } catch (BusinessException | QwenAssistantModelGateway.AssistantGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(500105, "知识库文档处理失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional
    public void delete(long userId, String documentId) {
        List<String> paths = jdbc.query("SELECT relative_path FROM ai_knowledge_document WHERE id=? AND user_id=?",
                (rs, row) -> rs.getString(1), documentId, userId);
        if (paths.isEmpty()) throw new BusinessException(404105, "知识库文档不存在", HttpStatus.NOT_FOUND);
        jdbc.update("DELETE FROM ai_knowledge_document WHERE id=? AND user_id=?", documentId, userId);
        Path stored = storageRoot.resolve(paths.getFirst()).normalize();
        if (!stored.startsWith(storageRoot)) return;
        try {
            Files.deleteIfExists(stored);
            Files.deleteIfExists(stored.getParent());
        } catch (Exception ignored) { }
    }

    public SearchResult search(long userId, String query, int limit, String traceId) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isBlank() || normalized.length() > 2_000) throw badRequest("知识检索问题必须为1至2000个字符");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id,c.document_id,c.chunk_index,c.content,c.embedding,d.original_name,d.embedding_model
                FROM ai_knowledge_chunk c JOIN ai_knowledge_document d ON d.id=c.document_id
                WHERE c.user_id=? AND d.user_id=? AND d.status='READY'
                ORDER BY d.updated_at DESC,c.chunk_index LIMIT ?
                """, userId, userId, MAX_RETRIEVAL_CHUNKS);
        if (rows.isEmpty()) return new SearchResult(null, List.of());
        AssistantEmbeddingGateway.EmbeddingBatch queryEmbedding = embeddingGateway.embed(List.of(normalized), traceId);
        List<Double> queryVector = queryEmbedding.vectors().getFirst();
        Set<String> queryTokens = tokens(normalized);
        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            try {
                List<Double> vector = mapper.readValue(String.valueOf(row.get("embedding")), new TypeReference<>() {});
                if (vector.size() != queryVector.size()) continue;
                String content = String.valueOf(row.get("content"));
                candidates.add(new Candidate(String.valueOf(row.get("id")), String.valueOf(row.get("document_id")),
                        String.valueOf(row.get("original_name")), ((Number) row.get("chunk_index")).intValue(),
                        content, cosine(queryVector, vector), lexical(queryTokens, tokens(content))));
            } catch (Exception ignored) { }
        }
        List<Candidate> vectorRank = candidates.stream().sorted(Comparator.comparingDouble(Candidate::vector).reversed()).toList();
        List<Candidate> lexicalRank = candidates.stream().filter(value -> value.lexical() > 0)
                .sorted(Comparator.comparingDouble(Candidate::lexical).reversed()).toList();
        Map<String, Double> rrf = new HashMap<>();
        for (int i = 0; i < vectorRank.size(); i++) rrf.merge(vectorRank.get(i).id(), 1d / (60 + i + 1), Double::sum);
        for (int i = 0; i < lexicalRank.size(); i++) rrf.merge(lexicalRank.get(i).id(), 1d / (60 + i + 1), Double::sum);
        List<Candidate> selected = candidates.stream().sorted(Comparator.comparingDouble(
                (Candidate value) -> rrf.getOrDefault(value.id(), 0d)).reversed()).limit(Math.max(1, Math.min(limit, 10))).toList();
        List<Evidence> evidence = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            Candidate value = selected.get(i);
            evidence.add(new Evidence("K" + (i + 1), value.documentId(), value.documentName(),
                    value.chunkIndex(), value.content(), rounded(rrf.getOrDefault(value.id(), 0d))));
        }
        return new SearchResult(queryEmbedding.model(), List.copyOf(evidence));
    }

    private record Candidate(String id, String documentId, String documentName, int chunkIndex,
                             String content, double vector, double lexical) {}

    private String extract(byte[] bytes, String originalName) {
        String extension = extension(originalName);
        if (".txt".equals(extension) || ".md".equals(extension)) {
            try {
                return new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "")
                        .replace("\r\n", "\n").strip();
            } catch (Exception exception) {
                throw badRequest("文本文件必须使用UTF-8编码");
            }
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARS);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
            Parser parser = ".pdf".equals(extension) ? new PDFParser() : new OOXMLParser();
            parser.parse(input, handler, metadata, new ParseContext());
            return handler.toString().replace("\u0000", "").replace("\r\n", "\n").strip();
        } catch (Exception exception) {
            throw badRequest("无法从该文档提取正文，请确认文件未损坏且不含密码");
        }
    }

    private List<String> chunks(String text) {
        String normalized = text.replaceAll("[\\t\\x0B\\f ]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + 1_200, normalized.length());
            if (end < normalized.length()) {
                int boundary = Math.max(normalized.lastIndexOf('\n', end),
                        Math.max(normalized.lastIndexOf('。', end), normalized.lastIndexOf('；', end)));
                if (boundary >= start + 650) end = boundary + 1;
            }
            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isBlank()) result.add(chunk);
            if (end >= normalized.length()) break;
            start = Math.max(start + 1, end - 160);
        }
        return result;
    }

    private Set<String> tokens(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        List<Character> chinese = new ArrayList<>();
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                if (!ascii.isEmpty()) { if (ascii.length() > 1) result.add(ascii.toString()); ascii.setLength(0); }
                chinese.add(c);
                result.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) ascii.append(c);
            else if (!ascii.isEmpty()) { if (ascii.length() > 1) result.add(ascii.toString()); ascii.setLength(0); }
        }
        if (ascii.length() > 1) result.add(ascii.toString());
        for (int i = 0; i + 1 < chinese.size(); i++) result.add("" + chinese.get(i) + chinese.get(i + 1));
        return result;
    }

    private double lexical(Set<String> query, Set<String> document) {
        if (query.isEmpty() || document.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(query);
        intersection.retainAll(document);
        return intersection.size() / Math.sqrt((double) query.size() * document.size());
    }

    private double cosine(List<Double> left, List<Double> right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i), b = right.get(i);
            dot += a * b; leftNorm += a * a; rightNorm += b * b;
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private double rounded(double value) { return Math.round(value * 1_000_000d) / 1_000_000d; }
    private String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }
    private String mediaType(MultipartFile file, String extension) {
        if (file.getContentType() != null && !file.getContentType().isBlank()) return file.getContentType();
        return switch (extension) {
            case ".pdf" -> "application/pdf";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".md" -> "text/markdown";
            default -> "text/plain";
        };
    }
    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private BusinessException badRequest(String message) {
        return new BusinessException(400105, message, HttpStatus.BAD_REQUEST);
    }
}
