package com.networkdisk.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(showSql = false)
@Import(KnowledgeService.class)
class KnowledgeServiceIntegrationTest {
    @Autowired private KnowledgeService service;
    @Autowired private KnowledgeNodeRepository nodes;
    @Autowired private KnowledgeEdgeRepository edges;
    @Autowired private UserRepository users;

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        owner = users.saveAndFlush(new User("graph-owner@example.com", "graph-owner", "hash"));
        other = users.saveAndFlush(new User("graph-other@example.com", "graph-other", "hash"));
    }

    @Test
    void storesNodesAndEdgesOnlyForTheCurrentOwner() {
        var java = service.createNode(owner.getId(), new KnowledgeNodeRequest("Java", "语言", "编程"));
        var jvm = service.createNode(owner.getId(), new KnowledgeNodeRequest("JVM", "虚拟机", "编程"));
        service.createNode(other.getId(), new KnowledgeNodeRequest("Private", "他人内容", null));

        var relation = service.createEdge(owner.getId(),
                new KnowledgeEdgeRequest(java.id(), jvm.id(), "包含"));

        assertThat(service.listNodes(owner.getId())).extracting(KnowledgeNodeResponse::title)
                .containsExactlyInAnyOrder("Java", "JVM");
        assertThat(service.listEdges(owner.getId())).containsExactly(relation);
        assertThat(service.listEdges(other.getId())).isEmpty();
    }

    @Test
    void refusesForeignNodesAndSelfLinksAndRemovesEdgesWithDeletedNodes() {
        var mine = service.createNode(owner.getId(), new KnowledgeNodeRequest("Mine", null, null));
        var foreign = service.createNode(other.getId(), new KnowledgeNodeRequest("Foreign", null, null));

        assertThatThrownBy(() -> service.createEdge(owner.getId(),
                new KnowledgeEdgeRequest(mine.id(), foreign.id(), "related")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> service.createEdge(owner.getId(),
                new KnowledgeEdgeRequest(mine.id(), mine.id(), "related")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(400);

        var second = service.createNode(owner.getId(), new KnowledgeNodeRequest("Second", null, null));
        service.createEdge(owner.getId(), new KnowledgeEdgeRequest(mine.id(), second.id(), "related"));
        service.deleteNode(owner.getId(), mine.id());

        assertThat(edges.count()).isZero();
        assertThat(nodes.findByIdAndOwnerId(foreign.id(), owner.getId())).isEmpty();
        assertThatThrownBy(() -> service.deleteNode(owner.getId(), foreign.id()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404);
    }
}
