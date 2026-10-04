package com.networkdisk.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_edge")
public class KnowledgeEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private KnowledgeNode source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private KnowledgeNode target;

    @Column(length = 50)
    private String relation;

    protected KnowledgeEdge() { }

    public KnowledgeEdge(KnowledgeNode source, KnowledgeNode target, String relation) {
        this.source = source;
        this.target = target;
        this.relation = relation;
    }

    public Long getId() { return id; }
    public KnowledgeNode getSource() { return source; }
    public KnowledgeNode getTarget() { return target; }
    public String getRelation() { return relation; }
}
