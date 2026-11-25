package ru.practicum.stats.client;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.MaxAttemptsRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class StatsClient {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long RETRY_BACKOFF_MS = 3000L;
    private static final int RETRY_MAX_ATTEMPTS = 3;

    private final RestTemplate restTemplate;
    private final String serverUrl;
    private final DiscoveryClient discoveryClient;
    private final String statsServiceId;
    private final RetryTemplate retryTemplate;

    public StatsClient(String serverUrl) {
        this(serverUrl, new RestTemplate());
    }

    public StatsClient(String serverUrl, RestTemplate restTemplate) {
        this.serverUrl = serverUrl;
        this.restTemplate = restTemplate;
        this.discoveryClient = null;
        this.statsServiceId = null;
        this.retryTemplate = null;
    }

    public StatsClient(DiscoveryClient discoveryClient, String statsServiceId) {
        this(discoveryClient, statsServiceId, new RestTemplate());
    }

    public StatsClient(DiscoveryClient discoveryClient, String statsServiceId, RestTemplate restTemplate) {
        this.serverUrl = null;
        this.restTemplate = restTemplate;
        this.discoveryClient = discoveryClient;
        this.statsServiceId = statsServiceId;
        this.retryTemplate = retryTemplate();
    }

    public void saveHit(String app, String uri, String ip) {
        EndpointHit hit = EndpointHit.builder()
                .app(app)
                .uri(uri)
                .ip(ip)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .build();

        restTemplate.postForEntity(resolveBase() + "/hit", hit, Void.class);
    }

    public void saveHit(EndpointHit hit) {
        restTemplate.postForEntity(resolveBase() + "/hit", hit, Void.class);
    }

    public List<ViewStats> getStats(LocalDateTime start, LocalDateTime end, List<String> uris, Boolean unique) {
        String startStr = start.format(FORMATTER);
        String endStr = end.format(FORMATTER);

        UriComponentsBuilder urlBuild = UriComponentsBuilder.fromHttpUrl(resolveBase() + "/stats")
                .queryParam("start", startStr)
                .queryParam("end", endStr);
        if (uris != null && !uris.isEmpty()) {
            urlBuild.queryParam("uris", uris.toArray());
        }

        if (Boolean.TRUE.equals(unique)) {
            urlBuild.queryParam("unique", true);
        }

        ResponseEntity<List<ViewStats>> response = restTemplate.exchange(
                urlBuild.build().encode().toUri(),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                }
        );

        return response.getBody() == null ? List.of() : response.getBody();
    }

    private String resolveBase() {
        if (discoveryClient == null) {
            return serverUrl;
        }
        ServiceInstance instance = retryTemplate.execute(context -> getInstance());
        return "http://" + instance.getHost() + ":" + instance.getPort();
    }

    private ServiceInstance getInstance() {
        try {
            return discoveryClient
                    .getInstances(statsServiceId)
                    .getFirst();
        } catch (Exception exception) {
            throw new StatsServerUnavailable(
                    "Ошибка обнаружения адреса сервиса статистики с id: " + statsServiceId,
                    exception
            );
        }
    }

    private static RetryTemplate retryTemplate() {
        RetryTemplate template = new RetryTemplate();

        FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(RETRY_BACKOFF_MS);
        template.setBackOffPolicy(fixedBackOffPolicy);

        MaxAttemptsRetryPolicy retryPolicy = new MaxAttemptsRetryPolicy();
        retryPolicy.setMaxAttempts(RETRY_MAX_ATTEMPTS);
        template.setRetryPolicy(retryPolicy);
        return template;
    }
}
