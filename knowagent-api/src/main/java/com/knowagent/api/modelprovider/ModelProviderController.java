package com.knowagent.api.modelprovider;

import com.knowagent.api.modelprovider.dto.CreateModelProviderRequest;
import com.knowagent.api.modelprovider.dto.HealthCheckResponse;
import com.knowagent.api.modelprovider.dto.ModelProviderPageResponse;
import com.knowagent.api.modelprovider.dto.ModelProviderResponse;
import com.knowagent.api.modelprovider.dto.UpdateModelProviderRequest;
import com.knowagent.model.application.service.CreateModelProviderCommand;
import com.knowagent.model.application.service.ModelProviderService;
import com.knowagent.model.application.service.UpdateModelProviderCommand;
import com.knowagent.model.provider.ModelProviderPage;
import com.knowagent.security.principal.TenantPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Model-provider management endpoints. The tenant id is always read from the
 * authenticated principal, never from the request body or a header; reads require
 * {@code MODEL_PROVIDER_READ} and mutations {@code MODEL_PROVIDER_WRITE}.
 */
@RestController
@RequestMapping("/api/v1/model-providers")
public class ModelProviderController {

    private final ModelProviderService service;

    public ModelProviderController(ModelProviderService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('MODEL_PROVIDER_WRITE')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelProviderResponse create(@AuthenticationPrincipal TenantPrincipal principal,
                                        @Valid @RequestBody CreateModelProviderRequest request) {
        return ModelProviderResponse.from(service.create(new CreateModelProviderCommand(
                principal.tenantId(), request.providerKey(), request.displayName(), request.adapterType(),
                request.baseUrl(), request.embeddingBaseUrl(), request.rerankBaseUrl(),
                request.capabilities(), request.enabledModels().stream().map(item -> item.toDomain()).toList(),
                request.publicConfig(), request.enabled(),
                request.secret(), request.headers(), principal.userId())));
    }

    @PreAuthorize("hasAuthority('MODEL_PROVIDER_READ')")
    @GetMapping
    public ModelProviderPageResponse list(@AuthenticationPrincipal TenantPrincipal principal,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        ModelProviderPage result = service.list(principal.tenantId(), page, size);
        List<ModelProviderResponse> items = result.providers().stream()
                .map(ModelProviderResponse::from)
                .toList();
        return new ModelProviderPageResponse(items, result.total(), page, size);
    }

    @PreAuthorize("hasAuthority('MODEL_PROVIDER_READ')")
    @GetMapping("/{id}")
    public ModelProviderResponse get(@AuthenticationPrincipal TenantPrincipal principal,
                                     @PathVariable UUID id) {
        return ModelProviderResponse.from(service.get(principal.tenantId(), id));
    }

    @PreAuthorize("hasAuthority('MODEL_PROVIDER_WRITE')")
    @PatchMapping("/{id}")
    public ModelProviderResponse update(@AuthenticationPrincipal TenantPrincipal principal,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody UpdateModelProviderRequest request) {
        return ModelProviderResponse.from(service.update(new UpdateModelProviderCommand(
                principal.tenantId(), id, request.providerKey(), request.displayName(), request.adapterType(),
                request.baseUrl(), request.embeddingBaseUrl(), request.rerankBaseUrl(),
                request.capabilities(), request.enabledModels() == null ? null
                        : request.enabledModels().stream().map(item -> item.toDomain()).toList(),
                request.publicConfig(), request.enabled(),
                request.secret(), request.headers(), request.clearSecret(), request.clearHeaders(),
                principal.userId())));
    }

    @PreAuthorize("hasAuthority('MODEL_PROVIDER_WRITE')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        service.delete(principal.tenantId(), id);
    }

    @PreAuthorize("hasAuthority('MODEL_PROVIDER_READ')")
    @PostMapping("/{id}/health-check")
    public HealthCheckResponse healthCheck(@AuthenticationPrincipal TenantPrincipal principal,
                                           @PathVariable UUID id) {
        return HealthCheckResponse.from(service.healthCheck(principal.tenantId(), id));
    }
}
