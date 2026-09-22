package com.fraud.project.service;

import java.util.List;

import com.fraud.project.entity.RiskAction;

/**
 * `RiskAction`'ların ciddiyet sırası — hem birden fazla kuralın eşleştiği
 * durumda (en ağır kural kazanır) hem de ML/Rule Engine sonuçlarının
 * escalate-only birleştirilmesinde kullanılıyor. Enum'un declaration sırasına
 * (ordinal) GÜVENMİYORUZ — biri enum'a araya yeni bir değer eklerse ordinal
 * sessizce bozulurdu, bu yüzden sıralama burada AÇIKÇA yazılı.
 */
public final class RiskActionSeverity {

    private static final List<RiskAction> ORDER = List.of(RiskAction.APPROVE, RiskAction.REVIEW, RiskAction.BLOCK);

    private RiskActionSeverity() {
    }

    public static RiskAction moreSevere(RiskAction a, RiskAction b) {
        return ORDER.indexOf(a) >= ORDER.indexOf(b) ? a : b;
    }
}
