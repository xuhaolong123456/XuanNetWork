package com.networkdisk.file;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

public interface SearchIndexStateRepository extends JpaRepository<SearchIndexState, Long> {
    @Query("select s from SearchIndexState s where s.generation <> s.indexedGeneration order by s.ownerId")
    List<SearchIndexState> findDirty(Pageable pageable);

    @Modifying
    @Transactional
    @Query("update SearchIndexState s set s.indexedGeneration = :generation where s.ownerId = :ownerId and s.generation = :generation")
    int markIndexedIfCurrent(@Param("ownerId") long ownerId, @Param("generation") long generation);
}
