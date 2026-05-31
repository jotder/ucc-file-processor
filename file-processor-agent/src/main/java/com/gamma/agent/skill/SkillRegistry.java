package com.gamma.agent.skill;

import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The intent→{@link Skill} table (v3.3.0). Dispatch resolves the request's intent to a skill and
 * runs it; an unknown intent yields {@link AssistResult#unsupported(String)} (mapped to HTTP 404
 * by the control plane). This is the surface the assist platform grows along — M4+ skills are new
 * entries here.
 */
public final class SkillRegistry {

    private final Map<String, Skill> byId;

    public SkillRegistry(List<Skill> skills) {
        Map<String, Skill> m = new LinkedHashMap<>();
        for (Skill s : skills) m.put(s.id(), s);
        this.byId = Map.copyOf(m);
    }

    public Optional<Skill> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The registered intents (skill ids). */
    public Set<String> intents() {
        return byId.keySet();
    }

    /** Run the skill bound to {@code request.intent()}, or report it unsupported. */
    public AssistResult dispatch(AssistRequest request, AssistContext ctx) {
        Skill skill = byId.get(request.intent());
        return (skill == null) ? AssistResult.unsupported(request.intent()) : skill.run(request, ctx);
    }
}
