package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sme.backend.entity.PaymentTransaction;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByCode(String code);
    Optional<PaymentTransaction> findByPayosOrderCode(Long payosOrderCode);
}