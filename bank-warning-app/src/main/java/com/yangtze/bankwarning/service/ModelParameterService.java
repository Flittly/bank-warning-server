package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.dto.ModelParameterUpdateRequest;
import com.yangtze.bankwarning.model.ParameterProfile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelParameterService {

    private final Map<String, ParameterProfile> profiles = new ConcurrentHashMap<>();

    public ParameterProfile update(ModelParameterUpdateRequest request) {
        ParameterProfile current = profiles.get(request.profileName());
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(request.merge()) && current != null) {
            parameters.putAll(current.parameters());
        }
        if (request.parameters() != null) {
            parameters.putAll(request.parameters());
        }

        ParameterProfile updated = new ParameterProfile(
                request.profileName(),
                request.modelApi(),
                parameters,
                Instant.now());
        profiles.put(request.profileName(), updated);
        return updated;
    }

    public List<ParameterProfile> list() {
        return profiles.values().stream().sorted((left, right) -> left.profileName().compareTo(right.profileName())).toList();
    }

    public Map<String, Object> resolve(String modelApi, List<String> profileNames) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        List<String> effectiveProfiles = (profileNames == null || profileNames.isEmpty())
                ? List.of("default")
                : profileNames;

        for (String profileName : effectiveProfiles) {
            ParameterProfile profile = profiles.get(profileName);
            if (profile != null && profile.matches(modelApi)) {
                resolved.putAll(profile.parameters());
            }
        }

        return resolved;
    }
}
