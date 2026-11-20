package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型服务 URI 的守门测试。
 *
 * <p>钉住的是「只编码一次」这条线：{@code tiff_key} 里带斜杠，一旦被编码两次就会出现
 * {@code %252F}，模型服务解码一次后拿到 {@code tiff%2F...} 的假路径，恒 404，而且还会
 * 白等一轮 RustFS 回源超时（实测 17 秒）。这类错误在单测里用「解码一次应还原为原始 key」
 * 才能钉住，光看日志里的 key 是看不出来的 —— 日志打的是编码前的值。
 */
class TiffServicePreviewUriTest {

    /** 线上真实出现的 key：刚好 6 段、带年份/月份、文件名与目录名不同。 */
    private static final String KEY = "tiff/YZ/2012/standard/201204/2012-YZ.tif";

    @Test
    void queryDecodesBackToTheOriginalKeyInOnePass() {
        URI uri = TiffService.buildModelServiceUri(
                "http://localhost:8088", "/v0/dem/preview", KEY, null);

        assertEquals("http", uri.getScheme());
        assertEquals("localhost", uri.getHost());
        assertEquals(8088, uri.getPort());
        assertEquals("/v0/dem/preview", uri.getPath());

        String rawQuery = uri.getRawQuery();
        assertFalse(rawQuery.contains("%25"), "出现 %25 说明被二次编码：" + uri);
        assertEquals("tiff_key=" + KEY,
                URLDecoder.decode(rawQuery, StandardCharsets.UTF_8),
                "解码一次必须还原成原始 key，否则模型服务会按假路径 404：" + uri);
    }

    @Test
    void parentDirectorySegmentsAreNotLost() {
        // 回归点：斜杠若被编码成 %2F 且再被编码一次，模型侧 split("/") 拿不到河段与时间点，
        // 会退化成「整串当文件名」再回源 RustFS，最后报 Resource not found。
        URI uri = TiffService.buildModelServiceUri(
                "http://localhost:8088", "/v0/dem/preview", KEY, null);
        String decoded = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        String key = decoded.substring("tiff_key=".length());
        String[] parts = key.split("/");
        assertEquals(6, parts.length, "河段/年份/数据集/时间点/文件名都要保留：" + key);
        assertEquals("YZ", parts[1]);
        assertEquals("201204", parts[4]);
    }

    @Test
    void maxSizeIsOnlyAppendedWhenPositive() {
        String base = "http://localhost:8088";

        assertFalse(TiffService.buildModelServiceUri(base, "/v0/dem/preview", KEY, null)
                .getRawQuery().contains("max_size"));
        assertFalse(TiffService.buildModelServiceUri(base, "/v0/dem/preview", KEY, 0)
                .getRawQuery().contains("max_size"));
        assertTrue(TiffService.buildModelServiceUri(base, "/v0/dem/preview", KEY, 1536)
                .getRawQuery().endsWith("&max_size=1536"));
    }

    @Test
    void trailingSlashInBaseUrlDoesNotProduceDoubleSlashPath() {
        URI uri = TiffService.buildModelServiceUri(
                "http://localhost:8088/", "/v0/dem/preview", KEY, null);
        assertEquals("/v0/dem/preview", uri.getPath());
    }

    @Test
    void deleteUriUsesTheSameSingleEncoding() {
        // 删除接口以前靠 Python 侧的 unquote 兜住了二次编码，属于巧合；这里一并钉住。
        URI uri = TiffService.buildModelServiceUri(
                "http://localhost:8088", "/api/v1/tiff/register", KEY, null);
        assertEquals("tiff_key=" + KEY,
                URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8));
    }

    @Test
    void manuallyEncodedKeysAreNotAcceptedAsAlreadyEncoded() {
        // 反向证明：如果调用方自己先 encode 再交给 RestTemplate，就会得到 %252F。
        String doubleEncoded = URLEncoder.encode(KEY, StandardCharsets.UTF_8);
        URI uri = TiffService.buildModelServiceUri(
                "http://localhost:8088", "/v0/dem/preview", doubleEncoded, null);
        // 这里我们要求「传进来的就是原始 key」，传预编码值会被如实再编码 —— 所以解码一次
        // 得到的是预编码串本身，正好等于「二次编码」的错误形态，用作对照。
        assertEquals("tiff_key=" + doubleEncoded,
                URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8));
    }
}
