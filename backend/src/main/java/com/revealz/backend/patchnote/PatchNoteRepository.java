package com.revealz.backend.patchnote;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface PatchNoteRepository extends JpaRepository<PatchNote, Long> {
    List<PatchNote> findByPublishedAtLessThanEqualOrderByPublishedAtDescIdDesc(Instant now, Pageable pageable);
    Optional<PatchNote> findByIdAndPublishedAtLessThanEqual(long id, Instant now);
    List<PatchNote> findAllByOrderByPublishedAtDescIdDesc(Pageable pageable);
}
