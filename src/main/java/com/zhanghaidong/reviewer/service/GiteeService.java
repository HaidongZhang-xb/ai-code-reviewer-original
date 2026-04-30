package com.zhanghaidong.reviewer.service;

import com.zhanghaidong.reviewer.dto.FileDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
     * GET /repos/{owner}/{repo}/pulls/{number}/files
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
     * 在 PR 上发表整体评论(非行级)
     * POST /repos/{owner}/{repo}/pulls/{number}/comments
     * 整体评论可以直接用 issue 评论接口,也可用 PR 评论接口的 body 参数
     */
    public void createPullRequestComment(String owner, String repo, Integer number, String body) {
        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl)
                .path("/repos/{owner}/{repo}/pulls/{number}/comments")
                .buildAndExpand(owner, repo, number)
                .toUriString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("access_token", accessToken);
        payload.put("body", body);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, jsonHeaders());

        try {
            restTemplate.postForEntity(url, entity, String.class);
            log.info("整体评论已发表: PR #{}", number);
        } catch (Exception e) {
            log.error("发表 PR 评论失败", e);
        }
    }

    /**
     * 行级评论(Day 2 用,Day 1 先打日志即可)
     * Gitee 行级评论 API 参数:
     *   path: 文件路径
     *   position: diff 中的位置(注意不是行号,是 patch 序号)
     *   body: 评论内容
     */
    public void createLineComment(String owner, String repo, Integer number,
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

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, jsonHeaders());

        try {
            restTemplate.postForEntity(url, entity, String.class);
            log.info("行级评论已发表: {} 第 {} 行", filePath, position);
        } catch (Exception e) {
            log.error("发表行级评论失败", e);
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }
}
