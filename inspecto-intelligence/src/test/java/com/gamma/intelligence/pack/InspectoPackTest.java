package com.gamma.intelligence.pack;

import com.eoiagent.app.ApplicationPack;
import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Shape checks for {@link InspectoPack} (AGT-5 P0) — mirrors eoiagent's own ReferenceApplicationPackTest. */
class InspectoPackTest {

    private final ApplicationPack pack = new InspectoPack(new CollectorService(List.of(), 3600, 1));

    @Test
    void metadataIdentifiesInspecto() {
        assertEquals("inspecto", pack.metadata().appId().value());
        assertNotNull(pack.metadata().name());
        assertNotNull(pack.metadata().version());
    }

    @Test
    void allProvidersAreNonNull() {
        assertNotNull(pack.metadata());
        assertNotNull(pack.modelProfile());
        assertNotNull(pack.knowledgeSources());
        assertNotNull(pack.toolProvider());
        assertNotNull(pack.navigationCatalog());
        assertNotNull(pack.promptProfile());
        assertNotNull(pack.policyProfile());
        assertNotNull(pack.config());
    }

    @Test
    void knowledgeSourceResolvesToAReadableGlossaryFile() {
        List<KnowledgeSource> sources = pack.knowledgeSources();
        assertEquals(1, sources.size());
        KnowledgeSource source = sources.get(0);
        assertFalse(source.resolve().isEmpty());
        for (DocumentSource doc : source.resolve()) {
            assertTrue(Files.isRegularFile(Path.of(doc.uri())), "readable file at " + doc.uri());
        }
    }

    @Test
    void toolBeltIsAllReadOnlyWithRoleAndCapabilityAndNoMcp() {
        List<Tool> tools = pack.toolProvider().tools();
        assertEquals(12, tools.size()); // glossary_lookup, docs_search, status_get, S5 signals_query, signal_timeline
                                        // + P1 timeline_build, diff_batches, config_versions_diff, anomaly_scan
                                        // + P2 component_draft, pipeline_author, suggest_expectations
        for (Tool t : tools) {
            assertFalse(t.spec().mutating(), "tool " + t.spec().name() + " must be read-only");
            assertNotNull(t.spec().requiredRole());
            assertNotNull(t.spec().capability());
        }
        assertTrue(pack.toolProvider().mcpServers().isEmpty());
    }

    @Test
    void navigationCatalogHasUniquePageIds() {
        List<PageDescriptor> pages = pack.navigationCatalog().pages();
        Set<String> ids = new HashSet<>();
        for (PageDescriptor page : pages) {
            assertTrue(ids.add(page.pageId()), "duplicate pageId " + page.pageId());
        }
        assertTrue(pack.navigationCatalog().find("overview").isPresent());
    }

    @Test
    void policyMapsRolesAndDefaultsToLeastPrivileged() {
        assertEquals(Role.ANALYST, pack.policyProfile().mapRole("analyst"));
        assertEquals(Role.ADMIN, pack.policyProfile().mapRole("admin"));
        assertEquals(Role.USER, pack.policyProfile().mapRole("nope"));
        assertEquals(Role.USER, pack.policyProfile().mapRole(null));

        Set<Capability> userGrants = pack.policyProfile().grants(Role.USER);
        assertTrue(userGrants.contains(Capability.READ_DOCS));
        assertFalse(userGrants.contains(Capability.WRITE_DATASTORE));
    }

    @Test
    void systemPromptIsNonBlankForEveryGoalKind() {
        for (GoalKind kind : GoalKind.values()) {
            assertNotNull(pack.promptProfile().systemPrompt(kind));
            assertFalse(pack.promptProfile().systemPrompt(kind).isBlank());
        }
        assertFalse(pack.promptProfile().persona().isBlank());
    }

    @Test
    void configProfileIsOfflineWithMutatingActionsAndMcpOff() {
        assertEquals(DeploymentProfile.OFFLINE, pack.config().profile());
        assertEquals(Boolean.FALSE, pack.config().featureOverrides().get(com.eoiagent.core.Feature.MUTATING_ACTIONS));
        assertEquals(Boolean.FALSE, pack.config().featureOverrides().get(com.eoiagent.core.Feature.MCP_TOOLS));
    }

    @Test
    void modelProfileNeverAllowsHostedFallback() {
        assertFalse(pack.modelProfile().routing().allowHostedFallback());
    }
}
