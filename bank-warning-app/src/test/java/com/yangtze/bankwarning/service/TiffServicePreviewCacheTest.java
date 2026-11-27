package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEM 预览图**不能**被 HTTP 缓存。
 *
 * <p>回归的坑：{@code Cache-Control: max-age=3600, public} 会让浏览器把灰度图存在
 * {@code ?tiff_key=..&ramp=hypsometric} 名下（URL 只由这两个参数决定，与「同一份 DEM
 * 的内容」无关）。之后无论怎么点「分层设色」，拿回的都是那份缓存，连
 * {@code X-Dem-Ramp: gray} 一起回来 ⇒ 前端 Segmented 的选中态立刻弹回灰度，用户看到的
 * 就是「点了没反应」，且重启后端无效（缓存在浏览器里）。
 */
class TiffServicePreviewCacheTest {

    @Test
    void previewResponseMustNotBeCacheable() throws Exception {
        TiffService service = new TiffService(null);

        Field baseUrl = TiffService.class.getDeclaredField("modelServiceBaseUrl");
        baseUrl.setAccessible(true);
        baseUrl.set(service, "http://localhost:8088");

        Field restTemplate = TiffService.class.getDeclaredField("restTemplate");
        restTemplate.setAccessible(true);
        restTemplate.set(service, new StubRestTemplate());

        ResponseEntity<byte[]> response = service.fetchPreview("tiff/a.tif", null, "hypsometric");

        String cacheControl = response.getHeaders().getCacheControl();
        assertNotNull(cacheControl, "必须显式声明缓存策略，不能留空让浏览器自己猜");
        assertTrue(cacheControl.contains("no-store"),
                "预览图是现场渲染的，内容随切片/配色变化，不能进任何缓存，实际：" + cacheControl);
        // 顺带确认响应头仍把配色回显给前端（图例用它画，不能因为改缓存头丢掉）
        assertEquals("hypsometric", response.getHeaders().getFirst("X-Dem-Ramp"));
    }

    /** 只拦 getForEntity：模拟模型服务返回一张带 X-Dem-* 的小图。 */
    private static final class StubRestTemplate extends RestTemplate {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> getForEntity(URI url, Class<T> responseType) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.add("X-Dem-Ramp", "hypsometric");
            return new ResponseEntity<>((T) new byte[]{1, 2, 3}, headers, HttpStatus.OK);
        }
    }
}
