package com.revealz.backend.auth;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class ExternalIdentityId implements Serializable {
    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_subject", nullable = false)
    private String providerSubject;

    protected ExternalIdentityId() { }

    ExternalIdentityId(String provider, String providerSubject) {
        this.provider = provider;
        this.providerSubject = providerSubject;
    }

    @Override public boolean equals(Object other) {
        return other instanceof ExternalIdentityId id
                && Objects.equals(provider, id.provider)
                && Objects.equals(providerSubject, id.providerSubject);
    }

    @Override public int hashCode() { return Objects.hash(provider, providerSubject); }
}
