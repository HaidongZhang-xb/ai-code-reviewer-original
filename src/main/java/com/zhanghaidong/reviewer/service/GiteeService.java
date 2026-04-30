package com.zhanghaidong.reviewer.service;

import com.zhanghaidong.reviewer.dto.FileDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gitee OpenAPI 客户端
 * 文档: https://gitee.com/api/v5/swagger
 *
 * @author 张海东
 */
@Slf4j
@Service
public class GiteeService {

    private final RestTemplate restTemplate;
    private final String accessToken;
    private final String apiBaseUrl;

    public GiteeService(RestTemplate restTemplate,
                        @Value("${gitee.access-token}") String accessToken,
                        @Value("${gitee.api-base-url}") String apiBaseUrl) {
        this.restTemplate = restTemplate;
        this.accessToken = accessToken;
        this.apiBaseUrl = apiBaseUrl;
    }

    /**
     * 获取 PR 的文件 diff 列表
     */
    public List<FileDiff> getPullRequestFiles(String owner, String repo, Integer number) {
        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl)
                .path("/repos/{owner}/{repo}/pulls/{number}/files")
                .queryParam("access_token", accessToken)
                .buildAndExpand(owner, repo, number)
                .toUriString();

        log.info("获取 PR 文件列表: owner={}, repo={}, number={}", owner, repo, number);

        ResponseEntity<List<FileDiff>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                new ParameterizedTypeReference<List<FileDiff>>() {}
        );

        List<FileDiff> files = response.getBody();
        log.info("拿到 {} 个变更文件", files == null ? 0 : files.size());
        return files;
    }

    /**
     * 在 PR 上发表整体评论(用作降级方案)
     */
    public boolean createPullRequestComment(String owner, String repo, Integer number, String body) {
        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl)
                .path("/repos/{owner}/{repo}/pulls/{number}/comments")
                .buildAndExpand(owner, repo, number)
                .toUriString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("access_token", accessToken);
        payload.put("body", body);

        try {
            restTemplate.postForEntity(url, new HttpEntity<>(payload, jsonHeaders()), String.class);
            log.info("整体评论已发表: PR #{}", number);
            return true;
        } catch (HttpClientErrorException e) {
            log.error("发表整体评论失败: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("发表整体评论失败", e);
            return false;
        }
    }

    /**
     * 行级评论
     * Gitee API: POST /repos/{owner}/{repo}/pulls/{number}/comments
     * 行级评论必须带 path + position 两个参数
     *
     * @param position diff 中的行序号(由 PatchPositionResolver 计算得出)
     */
    public boolean createLineComment(String owner, String repo, Integer number,
                                     String filePath, Integer position, String body) {
        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl)
                .path("/repos/{owner}/{repo}/pulls/{number}/comments")
                .buildAndExpand(owner, repo, number)
                .toUriString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("access_token", accessToken);
        payload.put("body", body);
        payload.put("path", filePath);
        payload.put("position", position);

        try {
            restTemplate.postForEntity(url, new HttpEntity<>(payload, jsonHeaders()), String.class);
            log.info("✅ 行级评论已发表: {} position={}", filePath, position);
            return true;
        } catch (HttpClientErrorException e) {
            log.warn("行级评论发表失败: status={}, file={}, position={}, body={}",
                    e.getStatusCode(), filePath, position, e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("行级评论发表失败: file={}, position={}", filePath, position, e);
            return false;
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }
    /**
     * 获取指定 ref(分支/sha)上某个文件的原文内容
     * Gitee API: GET /repos/{owner}/{repo}/contents/{path}
     *
     * @param ref 分支名或 commit sha
     * @return 文件原文,失败返回 null
     */
    public String getFileContent(String owner, String repo, String filePath, String ref) {
        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl)
                .path("/repos/{owner}/{repo}/contents/{path}")
                .queryParam("access_token", accessToken)
                .queryParam("ref", ref)
                .buildAndExpand(owner, repo, filePath)
                .toUriString();

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), Map.class);

            Map<?, ?> body = response.getBody();
            if (body == null) return null;

            String encoding = (String) body.get("encoding");
            String content = (String) body.get("content");
            if (content == null) return null;

            if ("base64".equalsIgnoreCase(encoding)) {
                // Gitee 返回的 base64 经常带换行,要先去掉
                content = content.replaceAll("\\s+", "");
                return new String(java.util.Base64.getDecoder().decode(content),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            return content;
        } catch (HttpClientErrorException e) {
            log.warn("拉取文件内容失败: file={}, ref={}, status={}, body={}",
                    filePath, ref, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("拉取文件内容异常: file={}, ref={}", filePath, ref, e);
            return null;
        }
    }
}