package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.TiffBoundsPO;
import com.yangtze.bankwarning.mapper.TiffBoundsMapper;
import com.yangtze.bankwarning.security.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEM 三维地形接口的守门测试。
 *
 * <p>钉的是三件事：
 * <ol>
 *   <li>DEM 按用户隔离：别人名下的 DEM 不能通过地形接口读到（既有的预览接口没做这层，这里不能跟着漏）；</li>
 *   <li>读不到身份一律拒绝（fail-closed），不能把「读不到」当成「管理员」；</li>
 *   <li>瓦片 URL 只编码一次 —— 与预览接口踩过的同一个坑。</li>
 * </ol>
 *
 * <p>模型服务地址指向一个必然连不上的端口：凡是**不该**发 HTTP 的分支，
 * 一旦误发就会以连接异常失败，测试自己会叫。
 */
@ExtendWith(MockitoExtension.class)
class TiffServiceTerrainAccessTest {

    private static final String KEY = "tiff/Yangtze/2023/standard/1998-2023/2023-Yangtze-uvhH.tif";
    private static final String FP = "921cd5752d5b178f";
    private static final long OWNER = 5L;

    @Mock
    TiffBoundsMapper mapper;

    TiffService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TiffService(mapper);
        Field field = TiffService.class.getDeclaredField("modelServiceBaseUrl");
        field.setAccessible(true);
        field.set(service, "http://127.0.0.1:1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 归属校验 ====================

    @Test
    void ownerCanReadOwnDem() {
        authenticate(OWNER, "ROLE_USER");
        TiffBoundsPO row = row(OWNER);
        when(mapper.selectByTiffKey(KEY)).thenReturn(row);

        assertSame(row, service.requireAccessibleTiff(KEY));
    }

    @Test
    void otherUserGetsNotFoundEvenThoughDemExists() {
        authenticate(OWNER + 1, "ROLE_USER");
        when(mapper.selectByTiffKey(KEY)).thenReturn(row(OWNER));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.requireAccessibleTiff(KEY));
        // 文案必须同时覆盖「不存在」与「无权」：单独说「无权」等于告诉对方这份 DEM 确实存在
        assertTrueContains(error.getMessage(), "不存在或无权访问");
    }

    @Test
    void adminSeesEveryDem() {
        authenticate(4L, "ROLE_SUPER_ADMIN");
        TiffBoundsPO row = row(OWNER);
        when(mapper.selectByTiffKey(KEY)).thenReturn(row);

        assertSame(row, service.requireAccessibleTiff(KEY));
    }

    @Test
    void missingIdentityIsDeniedWithoutTouchingDatabase() {
        // 读不到身份 ≠ 管理员。这一条就是 fail-open / fail-closed 的分界线。
        assertThrows(IllegalArgumentException.class, () -> service.requireAccessibleTiff(KEY));
        verify(mapper, never()).selectByTiffKey(anyString());
    }

    @Test
    void unknownDemIsNotFound() {
        authenticate(OWNER, "ROLE_USER");
        when(mapper.selectByTiffKey("tiff/none.tif")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainStatus("tiff/none.tif"));
    }

    @Test
    void blankKeyIsRejectedBeforeQuerying() {
        authenticate(OWNER, "ROLE_USER");

        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainStatus("  "));
        assertThrows(IllegalArgumentException.class, () -> service.requestTerrainBuild(null));
        verify(mapper, never()).selectByTiffKey(anyString());
    }

    @Test
    void buildAndStatusBothGoThroughOwnershipCheck() {
        authenticate(OWNER + 1, "ROLE_USER");
        when(mapper.selectByTiffKey(KEY)).thenReturn(row(OWNER));

        assertThrows(IllegalArgumentException.class, () -> service.requestTerrainBuild(KEY));
        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainStatus(KEY));
        // 两次调用都查了库（每次请求各判一次归属，不做缓存）
        verify(mapper, times(2)).selectByTiffKey(KEY);
    }

    // ==================== 瓦片 ====================

    @Test
    void malformedTileArgumentsAreRejectedBeforeQuerying() {
        authenticate(OWNER, "ROLE_USER");

        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainTile(KEY, "../etc", 12, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainTile(KEY, FP + "0", 12, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainTile(KEY, null, 12, 1, 1, null));
        // z12 一共 4096 列：列号 4096 越界
        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainTile(KEY, FP, 12, 4096, 1, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainTile(KEY, FP, 12, 1, -1, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchTerrainTile(KEY, FP, -1, 1, 1, null));
        verify(mapper, never()).selectByTiffKey(anyString());
    }

    @Test
    void foreignerCannotFetchTilesEvenWithValidFingerprint() {
        authenticate(OWNER + 1, "ROLE_USER");
        when(mapper.selectByTiffKey(KEY)).thenReturn(row(OWNER));

        assertThrows(IllegalArgumentException.class,
                () -> service.fetchTerrainTile(KEY, FP, 12, 3415, 1664, null));
    }

    @Test
    void matchingEtagShortCircuitsTo304WithoutCallingModelService() {
        authenticate(OWNER, "ROLE_USER");
        when(mapper.selectByTiffKey(KEY)).thenReturn(row(OWNER));
        String etag = TiffService.terrainEtag(FP, 12, 3415, 1664);
        assertNotNull(etag);

        ResponseEntity<byte[]> response = service.fetchTerrainTile(KEY, FP, 12, 3415, 1664, etag);

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
        assertNull(response.getBody());
        assertEquals(etag, response.getHeaders().getETag());
        assertEquals(TiffService.TERRAIN_TILE_CACHE_CONTROL,
                response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void etagQuotesFingerprintSoBrowsersEchoItVerbatim() {
        String etag = TiffService.terrainEtag(FP, 12, 3415, 1664);
        assertEquals("\"terrain-921cd5752d5b178f-12-3415-1664\"", etag);
    }

    @Test
    void tileUriEncodesTheKeyExactlyOnce() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tiff_key", KEY);
        params.put("fp", FP);
        params.put("z", 12);
        params.put("x", 3415);
        params.put("y", 1664);

        URI uri = TiffService.buildModelServiceUri("http://localhost:8088", "/v0/dem/terrain/tile", params);

        assertEquals("/v0/dem/terrain/tile", uri.getPath());
        String rawQuery = uri.getRawQuery();
        assertFalse(rawQuery.contains("%25"), "出现 %25 说明被二次编码：" + uri);
        assertEquals("tiff_key=" + KEY + "&fp=" + FP + "&z=12&x=3415&y=1664",
                URLDecoder.decode(rawQuery, StandardCharsets.UTF_8));
    }

    @Test
    void nonAsciiKeySurvivesOneRoundOfEncoding() {
        // 库里真实存在的 key：`tiff/Yangtze/2028/standard/202811/数模计算-5年后地形_30m.tif`
        String key = "tiff/Yangtze/2028/standard/202811/数模计算-5年后地形_30m.tif";
        URI uri = TiffService.buildModelServiceUri("http://localhost:8088/", "/v0/dem/terrain/status", key, null);
        assertEquals(key, URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8).substring("tiff_key=".length()));
    }

    @Test
    void previewUriBehaviourIsUnchangedByTheRefactor() {
        // 预览接口的 URI 构造改成了「委托通用版」，老行为必须逐字不变
        URI legacy = TiffService.buildModelServiceUri("http://localhost:8088", "/v0/dem/preview", KEY, 1536);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tiff_key", KEY);
        params.put("max_size", 1536);
        assertEquals(legacy, TiffService.buildModelServiceUri("http://localhost:8088", "/v0/dem/preview", params));

        // max_size 非正数时不追加 —— 与 TiffServicePreviewUriTest 钉的是同一条
        URI withoutMax = TiffService.buildModelServiceUri("http://localhost:8088", "/v0/dem/preview", KEY, 0);
        assertFalse(withoutMax.getRawQuery().contains("max_size"));
    }

    // ==================== helpers ====================

    private static TiffBoundsPO row(long userId) {
        TiffBoundsPO po = new TiffBoundsPO();
        po.setTiffKey(KEY);
        po.setUserId(userId);
        po.setTerrainStatus("ready");
        po.setTerrainFp(FP);
        po.setTerrainMaxzoom(12);
        return po;
    }

    private static void authenticate(long userId, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        var principal = new CustomUserDetails(userId, "user" + userId, "", authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private static void assertTrueContains(String actual, String expected) {
        assertNotNull(actual);
        if (!actual.contains(expected)) {
            throw new AssertionError("期望包含「" + expected + "」，实际：" + actual);
        }
    }
}
