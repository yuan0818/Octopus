package io.yuan.octopus.scenario.controller;

import io.yuan.octopus.scenario.entity.EnvProfile;
import io.yuan.octopus.scenario.repository.EnvProfileRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/env-profiles")
public class EnvProfileController {

    private final EnvProfileRepository repo;

    public EnvProfileController(EnvProfileRepository repo) { this.repo = repo; }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EnvProfile p : repo.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("baseUrl", p.getBaseUrl());
            row.put("hasSecrets", p.getSecretsEncrypted() != null);
            out.add(row);
        }
        return out;
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        EnvProfile p;
        if (body.get("id") instanceof Number n) {
            p = repo.findById(n.longValue()).orElseThrow();
        } else {
            p = new EnvProfile();
        }
        p.setName((String) body.get("name"));
        p.setBaseUrl((String) body.get("baseUrl"));
        // configJson / secrets P1 以明文 JSON 传入（密钥加密存储 P2 接 SecretCipher）
        if (body.get("configJson") != null) {
            p.setConfigJson(body.get("configJson") instanceof String s ? s : body.get("configJson").toString());
        }
        return ResponseEntity.ok(Map.of("id", repo.save(p).getId()));
    }
}
