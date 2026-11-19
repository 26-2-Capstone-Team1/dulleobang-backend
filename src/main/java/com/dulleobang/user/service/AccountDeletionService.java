package com.dulleobang.user.service;

import com.dulleobang.user.repository.AccountDeletionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionService {
  private final AccountDeletionRepository accounts;

  public AccountDeletionService(AccountDeletionRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional
  public void delete(UUID accountId) {
    accounts.delete(accountId);
  }
}
