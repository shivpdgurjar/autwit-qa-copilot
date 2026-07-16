package com.autwit.copilot.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.autwit.copilot.registry.SkillRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Powers the ⌘K palette. Read-only: skills are defined in the orchestrator's repo and
 * never edited here (SKILL_CONTRACT §2).
 */
@RestController
public class SkillController {

    private final SkillRepository skills;

    public SkillController(SkillRepository skills) {
        this.skills = skills;
    }

    @GetMapping("/skills")
    Map<String, Object> listSkills() {
        var body = new LinkedHashMap<String, Object>();
        body.put("catalog_version", skills.catalogVersion().orElse(null));
        body.put("synced_at", skills.syncedAt().orElse(null));
        body.put("skills", skills.list());
        return body;
    }
}
