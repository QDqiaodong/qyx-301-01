package com.example.salon.service;

import com.example.salon.entity.InvoiceNoSequence;
import com.example.salon.repository.InvoiceNoSequenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发票号分配器：独立事务（REQUIRES_NEW）里对计数器加行级写锁递增，
 * 即使外层开票事务回滚，已分配的流水也不回滚——票号天然不重号、不跳号复用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceNumberAllocator {

    private static final String GLOBAL_SCOPE = "GLOBAL";

    private final InvoiceNoSequenceRepository sequenceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextValue() {
        InvoiceNoSequence seq = sequenceRepository.findForUpdate(GLOBAL_SCOPE)
                .orElseGet(() -> {
                    InvoiceNoSequence created = new InvoiceNoSequence();
                    created.setScopeKey(GLOBAL_SCOPE);
                    created.setNextValue(1L);
                    return sequenceRepository.save(created);
                });
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        sequenceRepository.save(seq);
        return value;
    }
}
