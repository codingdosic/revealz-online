package com.revealz.backend.account;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletedAccountRepository extends JpaRepository<DeletedAccount, String> {
}
