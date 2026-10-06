package com.revealz.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, ExternalIdentityId> { }
