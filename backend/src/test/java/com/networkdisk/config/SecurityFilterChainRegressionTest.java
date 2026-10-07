package com.networkdisk.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networkdisk.auth.ActiveTokenRepository;
import com.networkdisk.auth.AuthController;
import com.networkdisk.auth.AuthCookie;
import com.networkdisk.auth.AuthService;
import com.networkdisk.auth.ChangePasswordRequest;
import com.networkdisk.auth.EmailCodeService;
import com.networkdisk.auth.LoginRequest;
import com.networkdisk.auth.LoginResponse;
import com.networkdisk.auth.TokenService;
import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import com.networkdisk.file.FileBreadcrumb;
import com.networkdisk.file.FileBusinessException;
import com.networkdisk.file.FileController;
import com.networkdisk.file.FileItemResponse;
import com.networkdisk.file.FileListResponse;
import com.networkdisk.file.FileNodeType;
import com.networkdisk.file.FilePageResponse;
import com.networkdisk.file.FileService;
import com.networkdisk.file.FileTrashService;
import com.networkdisk.file.FolderTreeNodeResponse;
import com.networkdisk.file.QuickCheckRequest;
import com.networkdisk.file.QuickCheckResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = {SecurityFilterChainRegressionTest.TestController.class, AuthController.class, FileController.class,
        com.networkdisk.file.ChunkUploadController.class, com.networkdisk.file.ListPartsController.class,
        com.networkdisk.file.DownloadArchiveService.class})
@Import({SecurityConfig.class, SecurityFilterChainRegressionTest.TestBeans.class,
        SecurityFilterChainRegressionTest.TestController.class, AuthController.class, FileController.class,
        com.networkdisk.file.ChunkUploadController.class, com.networkdisk.file.ListPartsController.class})
class SecurityFilterChainRegressionTest {
    private static final String DEVICE_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String CSRF_TOKEN = "csrf-test-token";

    @Autowired private MockMvc mvc;
    @Autowired private TokenService tokens;
    @Autowired private ActiveTokenRepository activeTokens;
    @Autowired private UserRepository users;
    @Autowired private AuthService authService;
    @Autowired private FileService fileService;
    @Autowired private FileTrashService trashService;
    @Autowired private com.networkdisk.file.ChunkUploadService chunkService;
    @Autowired private com.networkdisk.file.ListPartsService listPartsService;

    @Test
    void listPartsUsesDefaultsNumericProtocolAndReadOnlyGetAuthentication() throws Exception {
        mvc.perform(get("/api/v1/files/file/chunk-upload").param("identifier", "upload-test"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("用户未登录，请重新登录"));
        String token = tokens.create(42L, "hash-one");
        when(listPartsService.list(42L, "upload-test", 100, 0)).thenReturn(new com.networkdisk.file.ListPartsResponse(
                "upload-test", 3, java.util.List.of(new com.networkdisk.file.ListPartsResponse.UploadedChunk(1, 4, "")), false, 0));
        // GET 保持安全方法语义，不强制签发或验证新的 CSRF 令牌。
        mvc.perform(get("/api/v1/files/file/chunk-upload").cookie(new MockCookie(AuthCookie.NAME, token))
                        .param("identifier", "upload-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.uploadedChunks[0].size").value(4))
                .andExpect(jsonPath("$.data.nextPartNumberMarker").value(0));
        verify(listPartsService).list(42L, "upload-test", 100, 0);
        when(listPartsService.list(42L, "upload-test", 100, 0)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("测试数据库故障"));
        mvc.perform(get("/api/v1/files/file/chunk-upload").cookie(new MockCookie(AuthCookie.NAME, token))
                        .param("identifier", "upload-test"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value("服务暂时不可用"));
        mvc.perform(get("/api/v1/files/file/chunk-upload").cookie(new MockCookie(AuthCookie.NAME, token))
                        .param("identifier", "upload-test").param("maxParts", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listPartsReturns503ForAuthenticationDependencyAndTransactionFailures() throws Exception {
        String token = tokens.create(42L, "hash-one");
        when(listPartsService.list(42L, "upload-test", 100, 0)).thenThrow(
                new org.springframework.transaction.CannotCreateTransactionException("测试事务连接故障"));
        mvc.perform(get("/api/v1/files/file/chunk-upload").cookie(new MockCookie(AuthCookie.NAME, token))
                        .param("identifier", "upload-test"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
        when(activeTokens.isActive(token, 42L)).thenThrow(new RedisConnectionFailureException("测试认证依赖故障"));
        mvc.perform(get("/api/v1/files/file/chunk-upload").cookie(new MockCookie(AuthCookie.NAME, token))
                        .param("identifier", "upload-test"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value("服务暂时不可用"));
    }

    @Test
    void quickCheckMessageStatesWhetherInstantUploadWasHit() throws Exception {
        String token = tokens.create(42L, "hash-one");
        String body = "{\"fileHash\":\"" + "a".repeat(32)
                + "\",\"fileSize\":5,\"fileName\":\"notes.txt\",\"parentFolderId\":null}";
        when(fileService.quickCheck(eq(42L), any(QuickCheckRequest.class)))
                .thenReturn(new QuickCheckResponse(true, 37L));
        mvc.perform(post("/api/v1/files/quick-check").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("秒传命中"))
                .andExpect(jsonPath("$.data.exist").value(true))
                .andExpect(jsonPath("$.data.fileId").value(37));

        when(fileService.quickCheck(eq(42L), any(QuickCheckRequest.class)))
                .thenReturn(new QuickCheckResponse(false, null, "upload-test", 8388608L, 2));
        mvc.perform(post("/api/v1/files/quick-check").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("秒传未命中"))
                .andExpect(jsonPath("$.data.exist").value(false))
                .andExpect(jsonPath("$.data.uploadId").value("upload-test"));
    }

    @Test
    void chunkUploadUsesNumericProtocolAndRequiresAuthenticationAndCsrf() throws Exception {
        MockMultipartFile part = new MockMultipartFile("chunk", "abcd".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/files/file/chunk-upload").file(part))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("用户身份校验失败"));
        String token = tokens.create(42L, "hash-one");
        mvc.perform(multipart("/api/v1/files/file/chunk-upload").file(part)
                        .cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isForbidden());
        when(chunkService.upload(eq(42L), eq("large.txt"), eq("FILE"), eq(4L), eq("upload-test"), eq(1), eq("a".repeat(32)), any()))
                .thenReturn(new com.networkdisk.file.ChunkUploadResponse("upload-test", 1, com.networkdisk.file.MergeFlag.READY, java.util.List.of(1)));
        mvc.perform(multipart("/api/v1/files/file/chunk-upload").file(part).with(csrfRequest(token))
                        .param("name", "large.txt").param("node_type", "FILE").param("size_bytes", "4")
                        .param("uploadId", "upload-test").param("partNumber", "1").param("fileMd5", "a".repeat(32)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("分片上传成功"))
                .andExpect(jsonPath("$.data.mergeFlag").value(1));
        when(chunkService.upload(eq(42L), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), any()))
                .thenThrow(new FileBusinessException("40001", "uploadId不存在或者非法", 400));
        mvc.perform(multipart("/api/v1/files/file/chunk-upload").file(part).with(csrfRequest(token))
                        .param("name", "large.txt").param("node_type", "FILE").param("size_bytes", "4")
                        .param("uploadId", "unknown").param("partNumber", "1").param("fileMd5", "a".repeat(32)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        mvc.perform(multipart("/api/v1/files/file/chunk-upload").file(part).with(csrfRequest(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void chunkMergeUsesNumericProtocolAndRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(post("/api/v1/files/file/chunk-upload/complete").param("uploadId", "upload-test"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));

        String token = tokens.create(42L, "hash-one");
        mvc.perform(post("/api/v1/files/file/chunk-upload/complete").cookie(new MockCookie(AuthCookie.NAME, token))
                        .param("uploadId", "upload-test"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));

        when(fileService.mergeChunks(42L, "upload-test")).thenReturn(new FileItemResponse(91L, "large.txt",
                FileNodeType.FILE, 3L * 1024 * 1024 * 1024, "application/octet-stream",
                java.time.LocalDateTime.now()));
        mvc.perform(post("/api/v1/files/file/chunk-upload/complete").with(csrfRequest(token))
                        .param("uploadId", "upload-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("分片合并成功"))
                .andExpect(jsonPath("$.data.id").value(91))
                .andExpect(jsonPath("$.data.name").value("large.txt"));
        verify(fileService).mergeChunks(42L, "upload-test");

        when(fileService.mergeChunks(42L, "upload-test")).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("test database outage"));
        mvc.perform(post("/api/v1/files/file/chunk-upload/complete").with(csrfRequest(token))
                        .param("uploadId", "upload-test"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
    }

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<User> user = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(listPartsService);
        active.set(true);
        user.set(new User("user@example.com", "user001", "hash-one"));
        when(authService.tokenTtlSeconds()).thenReturn(7200L);
        when(activeTokens.isActive(anyString(), eq(42L))).thenAnswer(invocation -> active.get());
        when(users.findById(42L)).thenAnswer(invocation -> Optional.of(user.get()));
    }

    @Test
    void publicLoginRouteSetsHttpOnlyCookieAndDoesNotReturnTokenInJson() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString(), anyString()))
                .thenReturn(new LoginResponse("issued-token", 42L, "user001"));

        mvc.perform(post("/api/v1/auth/login").with(csrfRequest(null))
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user001\",\"password\":\"123456\",\"captchaCode\":\"2345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.username").value("user001"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString(
                        AuthCookie.NAME + "=issued-token;")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    @Test
    void protectedRouteRejectsMissingMalformedTamperedAndExpiredCookies() throws Exception {
        mvc.perform(get("/api/v1/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, "malformed")))
                .andExpect(status().isUnauthorized());

        String token = tokens.create(42L, "hash-one");
        mvc.perform(get("/api/v1/private")
                        .cookie(new MockCookie(AuthCookie.NAME, token.substring(0, token.length() - 2) + "AA")))
                .andExpect(status().isUnauthorized());
        TokenService expiredTokens = new TokenService(TestBeans.secret(), -1);
        mvc.perform(get("/api/v1/private")
                        .cookie(new MockCookie(AuthCookie.NAME, expiredTokens.create(42L, "hash-one"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteReadsAuthenticatedUserFromCookieAndIgnoresBearerHeader() throws Exception {
        String token = tokens.create(42L, "hash-one");
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42));
        mvc.perform(get("/api/v1/private").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fileListRequiresCookieAuthenticationAndUsesPrincipalAsOwner() throws Exception {
        mvc.perform(get("/api/v1/files"))
                .andExpect(status().isUnauthorized());

        String token = tokens.create(42L, "hash-one");
        when(fileService.list(42L, 13L, 0, 50)).thenReturn(new FileListResponse(
                null, java.util.List.of(new FileBreadcrumb(null, "我的文件")), java.util.List.of(),
                new FilePageResponse(0, 50, 0, 0)));
        mvc.perform(get("/api/v1/files").param("parentId", "13")
                        .cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.breadcrumbs[0].name").value("我的文件"));
        verify(fileService).list(42L, 13L, 0, 50);
    }

    @Test
    void directoryCreationRequiresAuthenticationAndCsrfAndUsesPrincipalAsOwner() throws Exception {
        String token = tokens.create(42L, "hash-one");
        mvc.perform(post("/api/v1/files/directories").with(csrfRequest(null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"folderName\":\"Reports\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/files/directories").cookie(new MockCookie(AuthCookie.NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"folderName\":\"Reports\"}"))
                .andExpect(status().isForbidden());

        when(fileService.createDirectory(42L, 13L, "Reports"))
                .thenReturn(new FileItemResponse(21L, "Reports", FileNodeType.DIRECTORY, 0, null, null));
        mvc.perform(post("/api/v1/files/directories").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":13,\"folderName\":\"Reports\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Reports"))
                .andExpect(jsonPath("$.data.type").value("DIRECTORY"));
        verify(fileService).createDirectory(42L, 13L, "Reports");
    }

    @Test
    void directoryCreationRejectsBlankAndOverlongNamesWithInvalidParam() throws Exception {
        String token = tokens.create(42L, "hash-one");
        mvc.perform(post("/api/v1/files/directories").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"folderName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        mvc.perform(post("/api/v1/files/directories").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"folderName\":\"" + "a".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void directoryCreationReturnsNotFoundWhenParentIsForeign() throws Exception {
        String token = tokens.create(42L, "hash-one");
        when(fileService.createDirectory(eq(42L), eq(999L), eq("Reports")))
                .thenThrow(new FileBusinessException("FILE_NOT_FOUND", "Directory not found", 404));

        mvc.perform(post("/api/v1/files/directories").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":999,\"folderName\":\"Reports\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void directoryRenameAndDeleteRequireAuthenticationAndCsrf() throws Exception {
        String token = tokens.create(42L, "hash-one");
        mvc.perform(patch("/api/v1/files/directories/13").with(csrfRequest(null))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"folderName\":\"Archive\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/files/directories/13").cookie(new MockCookie(AuthCookie.NAME, token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"folderName\":\"Archive\"}"))
                .andExpect(status().isForbidden());
        when(fileService.renameDirectory(42L, 13L, "Archive"))
                .thenReturn(new FileItemResponse(13L, "Archive", FileNodeType.DIRECTORY, 0, null, null));
        mvc.perform(patch("/api/v1/files/directories/13").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"folderName\":\"Archive\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Archive"));
        verify(fileService).renameDirectory(42L, 13L, "Archive");

        mvc.perform(delete("/api/v1/files/directories/13").with(csrfRequest(null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/files/directories/13").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/files/directories/13").with(csrfRequest(token)))
                .andExpect(status().isNoContent());
        verify(trashService).delete(42L, java.util.List.of(13L));
    }

    @Test
    void trashEndpointsRequireAuthenticationCsrfAndValidIds() throws Exception {
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/files");
        mvc.perform(put.contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[13]}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        String token = tokens.create(42L, "hash-one");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/files")
                        .cookie(new MockCookie(AuthCookie.NAME, token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[13]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        for (String body : java.util.List.of("{\"ids\":[]}", "{\"ids\":[null]}", "{\"ids\":[0]}", "{")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/files")
                            .with(csrfRequest(token)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/files")
                        .with(csrfRequest(token)).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[13,14]}"))
                .andExpect(status().isNoContent()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        verify(trashService).delete(42L, java.util.List.of(13L, 14L));
    }

    @Test
    void trashErrorsAndRecoveryUseTheDocumentedContract() throws Exception {
        String token = tokens.create(42L, "hash-one");
        for (int code : java.util.List.of(403, 404)) {
            org.mockito.Mockito.doThrow(new FileBusinessException("FAIL", "无法删除", code))
                    .when(trashService).delete(42L, java.util.List.of((long) code));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/file/" + code)
                            .with(csrfRequest(token)))
                    .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.msg").value("无法删除"));
        }
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database offline"))
                .when(trashService).delete(42L, java.util.List.of(500L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/file/500")
                        .with(csrfRequest(token)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("操作失败，请稍后重试"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/recover/13")
                        .with(csrfRequest(token))).andExpect(status().isNoContent());
        verify(trashService).restore(42L, java.util.List.of(13L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/files/recover/batch")
                        .with(csrfRequest(token)).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[13,14]}"))
                .andExpect(status().isNoContent());
        verify(trashService).restore(42L, java.util.List.of(13L, 14L));
        when(trashService.list(42L, 0, 50)).thenReturn(new com.networkdisk.file.TrashListResponse(
                java.util.List.of(), new FilePageResponse(0, 50, 0, 0)));
        mvc.perform(get("/api/v1/files/recycle-bin").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void previewRequiresAuthenticationAndReturnsWrappedContentWithoutCaching() throws Exception {
        mvc.perform(get("/api/v1/files/file/preview").param("fileId", "12"))
                .andExpect(status().isUnauthorized());
        String token = tokens.create(42L, "hash-one");
        when(fileService.preview(42L, 12L)).thenReturn(
                new com.networkdisk.file.FilePreviewResponse(12L, "a.md", "md", "# 中文"));
        mvc.perform(get("/api/v1/files/file/preview").param("fileId", "12").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("# 中文"))
                .andExpect(jsonPath("$.data.format").value("md"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(fileService).preview(42L, 12L);
        mvc.perform(get("/api/v1/files/file/preview").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/v1/files/file/preview").param("fileId", "abc").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isBadRequest());
        when(fileService.preview(42L, 99L)).thenThrow(new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404));
        mvc.perform(get("/api/v1/files/file/preview").param("fileId", "99").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void folderTreeReturns404WithoutLogin204WhenEmptyAndNestedNodesWhenPresent() throws Exception {
        mvc.perform(get("/api/v1/files/file/tree"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        String token = tokens.create(42L, "hash-one");
        when(fileService.folderTree(42L)).thenReturn(java.util.List.of());
        mvc.perform(get("/api/v1/files/file/tree").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isNoContent());
        FolderTreeNodeResponse root = new FolderTreeNodeResponse(1L, 0L, "根目录");
        root.children().add(new FolderTreeNodeResponse(2L, 1L, "子目录"));
        when(fileService.folderTree(42L)).thenReturn(java.util.List.of(root));
        mvc.perform(get("/api/v1/files/file/tree").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].parentId").value(0))
                .andExpect(jsonPath("$.data[0].children[0].name").value("子目录"));
        verify(fileService, org.mockito.Mockito.times(2)).folderTree(42L);
    }

    @Test
    void downloadStreamsOwnedFileAndSeparatesParameterErrorFromMissingResource(@TempDir Path directory) throws Exception {
        mvc.perform(get("/api/v1/files/file/download").param("filename", "说明.txt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        String token = tokens.create(42L, "hash-one");
        Path stored = directory.resolve("content");
        Files.writeString(stored, "文件内容", StandardCharsets.UTF_8);
        when(fileService.download(42L, "说明.txt", 12L))
                .thenReturn(new FileService.FileDownload("说明.txt", stored));
        mvc.perform(get("/api/v1/files/file/download").param("filename", "说明.txt")
                        .param("fileId", "12").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .bytes("文件内容".getBytes(StandardCharsets.UTF_8)));
        verify(fileService).download(42L, "说明.txt", 12L);
        mvc.perform(get("/api/v1/files/file/download").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/v1/files/file/download").param("filename", "说明.txt")
                        .param("fileId", "bad").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        when(fileService.download(42L, "missing.txt", null))
                .thenThrow(new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404));
        mvc.perform(get("/api/v1/files/file/download").param("filename", "missing.txt")
                        .cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void batchDownloadRequiresAuthenticationAndStreamsZip(@TempDir Path directory) throws Exception {
        String body = "{\"ids\":[12,13],\"downloadName\":\"files\"}";
        mvc.perform(post("/api/v1/files/files/download").with(csrfRequest(null))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String token = tokens.create(42L, "hash-one");
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        Files.writeString(first, "one");
        Files.writeString(second, "two");
        when(fileService.downloadMany(42L, java.util.List.of(12L, 13L)))
                .thenReturn(java.util.List.of(new FileService.FileDownload("same.txt", first),
                        new FileService.FileDownload("same.txt", second)));
        MvcResult pending = mvc.perform(post("/api/v1/files/files/download").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        MvcResult completed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("files.zip")))
                .andReturn();
        try (var zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(completed.getResponse().getContentAsByteArray()), StandardCharsets.UTF_8)) {
            org.assertj.core.api.Assertions.assertThat(zip.getNextEntry().getName()).isEqualTo("same.txt");
            org.assertj.core.api.Assertions.assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("one");
            org.assertj.core.api.Assertions.assertThat(zip.getNextEntry().getName()).isEqualTo("same（1）.txt");
            org.assertj.core.api.Assertions.assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("two");
        }
        verify(fileService).downloadMany(42L, java.util.List.of(12L, 13L));
    }

    @Test
    void emptyUploadReturnsDistinctBusinessCodeWith401() throws Exception {
        String token = tokens.create(42L, "hash-one");
        when(fileService.upload(eq(42L), eq(null), any()))
                .thenThrow(new FileBusinessException("EMPTY_FILE", "不能上传空文件", 401));
        mvc.perform(multipart("/api/v1/files/upload")
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                        .with(csrfRequest(token)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));
        org.mockito.Mockito.reset(fileService);
    }

    @Test
    void fileUploadRequiresAuthenticationAndUsesPrincipalAsOwner() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
        mvc.perform(multipart("/api/v1/files/upload").file(file).with(csrfRequest(null)))
                .andExpect(status().isUnauthorized());

        String token = tokens.create(42L, "hash-one");
        when(fileService.upload(eq(42L), eq(13L), any()))
                .thenReturn(new FileItemResponse(22L, "notes.txt", FileNodeType.FILE, 5, "text/plain", null));
        mvc.perform(multipart("/api/v1/files/upload").file(file).with(csrfRequest(token)).param("parentId", "13"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("notes.txt"))
                .andExpect(jsonPath("$.data.sizeBytes").value(5));
        verify(fileService).upload(eq(42L), eq(13L), any());
    }

    @Test
    void loginLogoutThenLoginAgainRevokesOldCookieAndKeepsNewCookieValid() throws Exception {
        Set<String> activeSessionTokens = new HashSet<>();
        when(activeTokens.isActive(anyString(), eq(42L))).thenAnswer(invocation ->
                activeSessionTokens.contains(invocation.getArgument(0, String.class)));
        when(authService.login(any(LoginRequest.class), anyString(), anyString())).thenAnswer(invocation -> {
            String token = tokens.create(42L, "hash-one");
            activeSessionTokens.add(token);
            return new LoginResponse(token, 42L, "user001");
        });
        doAnswer(invocation -> {
            activeSessionTokens.remove(invocation.getArgument(0, String.class));
            return null;
        }).when(authService).logout(anyString());

        String firstToken = loginAndGetCookieToken(CSRF_TOKEN);
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, firstToken)))
                .andExpect(status().isOk());

        MvcResult logoutResult = mvc.perform(post("/api/v1/auth/logout").with(csrfRequest(firstToken)))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(logoutResult.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(value -> value.contains(AuthCookie.NAME + "=; Path=/; Max-Age=0"))
                .anyMatch(value -> value.contains("XSRF-TOKEN=; Path=/; Max-Age=0"));
        verify(authService).logout(firstToken);
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, firstToken)))
                .andExpect(status().isUnauthorized());

        MvcResult staleCsrfLogin = mvc.perform(post("/api/v1/auth/login").with(csrfHeaderOnlyRequest(CSRF_TOKEN))
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isForbidden())
                .andReturn();
        String refreshedCsrfToken = staleCsrfLogin.getResponse().getCookie("XSRF-TOKEN").getValue();
        String secondToken = loginAndGetCookieToken(refreshedCsrfToken);
        org.assertj.core.api.Assertions.assertThat(secondToken).isNotEqualTo(firstToken);
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, secondToken)))
                .andExpect(status().isOk());
    }

    @Test
    void revokedTokenIsRejectedOnNextRequest() throws Exception {
        String token = tokens.create(42L, "hash-one");
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk());
        doAnswer(invocation -> { active.set(false); return null; })
                .when(authService).logout(token);
        mvc.perform(post("/api/v1/auth/logout").with(csrfRequest(token)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeInvalidatesEarlierTokenEvenWhenRedisEntryStillExists() throws Exception {
        String token = tokens.create(42L, "hash-one");
        doAnswer(invocation -> { user.get().changePasswordHash("hash-two"); return null; })
                .when(authService).changePassword(eq(42L), any(ChangePasswordRequest.class));
        mvc.perform(post("/api/v1/auth/change-password").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redisFailureFailsClosedInsteadOfAcceptingSignedCookie() throws Exception {
        String token = tokens.create(42L, "hash-one");
        when(activeTokens.isActive(token, 42L))
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"));
    }

    private String loginAndGetCookieToken(String csrfToken) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrfRequest(null, csrfToken))
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(AuthCookie.NAME + "="))
                .map(value -> value.substring((AuthCookie.NAME + "=").length(), value.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Login did not set the authentication cookie"));
    }

    private RequestPostProcessor csrfRequest(String authToken) {
        return csrfRequest(authToken, CSRF_TOKEN);
    }

    private RequestPostProcessor csrfRequest(String authToken, String csrfToken) {
        return request -> {
            MockCookie csrfCookie = new MockCookie("XSRF-TOKEN", csrfToken);
            if (authToken == null) request.setCookies(csrfCookie);
            else request.setCookies(csrfCookie, new MockCookie(AuthCookie.NAME, authToken));
            request.addHeader("X-XSRF-TOKEN", csrfToken);
            return request;
        };
    }

    private RequestPostProcessor csrfHeaderOnlyRequest(String csrfToken) {
        return request -> {
            request.addHeader("X-XSRF-TOKEN", csrfToken);
            return request;
        };
    }

    private String loginBody() {
        return "{\"username\":\"user001\",\"password\":\"123456\",\"captchaCode\":\"2345\"}";
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestBeans {
        @Bean
        com.networkdisk.file.ListPartsService listPartsService() {
            return mock(com.networkdisk.file.ListPartsService.class);
        }
        @Bean
        com.networkdisk.file.ChunkUploadService chunkUploadService() {
            return mock(com.networkdisk.file.ChunkUploadService.class);
        }
        static String secret() {
            return Base64.getEncoder().encodeToString(
                    "regression-test-key-longer-than-32-bytes".getBytes(StandardCharsets.UTF_8));
        }

        @Bean
        TokenService tokenService() {
            return new TokenService(secret(), 3600);
        }

        @Bean
        ActiveTokenRepository activeTokenRepository() { return mock(ActiveTokenRepository.class); }

        @Bean
        UserRepository userRepository() { return mock(UserRepository.class); }

        @Bean
        AuthService authService() { return mock(AuthService.class); }

        @Bean
        EmailCodeService emailCodeService() { return mock(EmailCodeService.class); }

        @Bean
        FileService fileService() { return mock(FileService.class); }
        @Bean
        FileTrashService trashService() { return mock(FileTrashService.class); }
    }

    @RestController
    static class TestController {
        @GetMapping(value = "/api/v1/private", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Long> privateRoute(Authentication authentication) {
            return Map.of("userId", (Long) authentication.getPrincipal());
        }
    }
}
