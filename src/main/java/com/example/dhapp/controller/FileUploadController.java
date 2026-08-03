package com.example.dhapp.controller;

import java.nio.file.Files;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.dhapp.dto.FileUploadResponse;
import com.example.dhapp.dto.UploadInfoResponse;
import com.example.dhapp.service.DummyPdfService;
import com.example.dhapp.service.FileUploadService;
import com.example.dhapp.service.FileUploadService.ResolvedTempDir;

/**
 * ファイルアップロード API。
 *
 * <ul>
 *   <li>{@code POST /api/file/upload} … multipart/form-data で受け取ったファイルを
 *       AP サーバのテンポラリフォルダへ保存し、保存場所とサイズをログとレスポンスに出力する</li>
 *   <li>{@code GET /api/file/upload-info} … ファイルを送らずに保存先テンポラリフォルダと
 *       適用中の上限を確認する</li>
 * </ul>
 *
 * <p>サイズ上限を超えた場合（アプリ側 {@code spring.servlet.multipart.*} / AP サーバ側
 * {@code max-post-size}）は、このコントローラに到達する前に例外となるため
 * {@code GlobalExceptionHandler} が詳細な 413 レスポンスを返す。</p>
 *
 * <p>利用方法の詳細は {@code FILE_UPLOAD_API.md} を参照。</p>
 */
@RestController
@RequestMapping("/api/file")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    /** curl の -F で指定するパート名。 */
    private static final String FILE_PART_NAME = "file";

    private final FileUploadService fileUploadService;
    private final DummyPdfService dummyPdfService;

    public FileUploadController(FileUploadService fileUploadService, DummyPdfService dummyPdfService) {
        this.fileUploadService = fileUploadService;
        this.dummyPdfService = dummyPdfService;
    }

    /**
     * ファイルを受け取り、AP サーバのテンポラリフォルダへ保存する。
     *
     * <pre>
     * curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
     *   -F "file=@/path/to/sample.bin" \
     *   -F "note=upload test"
     * </pre>
     *
     * @param file 受信するファイル（パート名は {@code file}）
     * @param note 任意の付随メッセージ
     */
    @PostMapping(value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileUploadResponse> upload(
            @RequestParam(name = FILE_PART_NAME) MultipartFile file,
            @RequestParam(name = "note", required = false) String note,
            HttpServletRequest request) {

        long startedAt = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/file/upload received. requestId={}, originalFilename={}, declaredSizeBytes={}, "
                        + "contentType={}, contentLength={}",
                requestId, file.getOriginalFilename(), file.getSize(), file.getContentType(),
                request.getContentLengthLong());

        // 各 REST API 呼び出し時に DATA_OUTPUT_DIR 配下へ "DUMMY" と記載した PDF を生成する。
        dummyPdfService.createDummyPdf("file-upload");

        FileUploadResponse response = fileUploadService.store(file, FILE_PART_NAME, note, requestId);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        response.setElapsedMs(elapsedMs);
        log.info("POST /api/file/upload done. requestId={}, status={}, storedPath={}, sizeBytes={}, "
                        + "sizeReadable={}, tempDirSource={}, elapsedMs={}",
                requestId, response.getStatus(), response.getStoredPath(), response.getSizeBytes(),
                response.getSizeReadable(), response.getTempDirSource(), elapsedMs);
        return ResponseEntity.ok(response);
    }

    /**
     * ファイルを送らずに、保存先テンポラリフォルダと適用中のアップロード上限を確認する。
     * max_post_size 超過テストの前に、どのサイズでどちらの上限に当たるかを把握するために使う。
     */
    @GetMapping(value = "/upload-info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadInfoResponse> uploadInfo() {
        ResolvedTempDir tempDir = fileUploadService.resolveTempDir();
        boolean exists = Files.isDirectory(tempDir.directory());

        UploadInfoResponse response = new UploadInfoResponse();
        response.setStatus("OK");
        response.setTempDirectory(tempDir.directory().toString());
        response.setTempDirSource(tempDir.source());
        response.setTempDirectoryExists(exists);
        response.setTempDirectoryWritable(exists && Files.isWritable(tempDir.directory()));
        response.setLimits(fileUploadService.buildLimitsInfo());

        log.info("GET /api/file/upload-info done. tempDirectory={}, tempDirSource={}, exists={}, writable={}, "
                        + "maxFileSizeBytes={}, maxRequestSizeBytes={}",
                response.getTempDirectory(), response.getTempDirSource(), response.isTempDirectoryExists(),
                response.isTempDirectoryWritable(), response.getLimits().getMaxFileSizeBytes(),
                response.getLimits().getMaxRequestSizeBytes());
        return ResponseEntity.ok(response);
    }
}
