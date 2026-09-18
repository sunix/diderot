package org.sunix.diderot.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Who a skill's signature must come from: the identity in the signing certificate, and the OIDC
 * issuer that vouched for it. Both halves are needed, because an identity string means nothing
 * without saying who is entitled to assert it — anyone can run a workflow file with the same path
 * under a different issuer.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Signer {

    /** The certificate's subject alternative name, e.g. a GitHub workflow ref. */
    public String identity;

    /** The OIDC issuer, e.g. {@code https://token.actions.githubusercontent.com}. */
    public String issuer;

    public Signer() {
    }

    public Signer(String identity, String issuer) {
        this.identity = identity;
        this.issuer = issuer;
    }

    @Override
    public String toString() {
        return identity + " (" + issuer + ")";
    }
}
