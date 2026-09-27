package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class RuleSelectorSemanticsTest {

    @Test
    void predicateCardinalityAndCanonicalValuesAreFailClosed() {
        assertThat(new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.ROLE, SelectorOperator.EQUALS,
                List.of("web.http-controller")).values())
                .containsExactly("web.http-controller");
        assertThat(new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.PATH, SelectorOperator.GLOB,
                List.of("**/*Controller.java", "**/*Endpoint.java")).values())
                .containsExactly("**/*Controller.java", "**/*Endpoint.java");
        assertThat(new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.SYMBOL, SelectorOperator.PRESENT,
                List.of()).values()).isEmpty();
        assertThat(new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.SYMBOL, SelectorOperator.GLOB,
                List.of("**Controller")).values()).containsExactly("**Controller");

        assertThatThrownBy(() -> new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.ROLE, SelectorOperator.EQUALS, List.of("a", "b")))
                .hasMessageContaining("EQUALS requires exactly one value");
        assertThatThrownBy(() -> new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.ROLE, SelectorOperator.IN, List.of()))
                .hasMessageContaining("IN requires");
        assertThatThrownBy(() -> new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.ROLE, SelectorOperator.PRESENT, List.of("x")))
                .hasMessageContaining("PRESENT requires no values");
        assertThatThrownBy(() -> new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.ROLE, SelectorOperator.GLOB, List.of("web.*")))
                .hasMessageContaining("GLOB is supported only for PATH");
        assertThatThrownBy(() -> new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.ROLE, SelectorOperator.IN, List.of("controller", "controller")))
                .hasMessageContaining("duplicate selector values");
    }

    @Test
    void selectorGroupMustHaveAStableKeyAndAtLeastOneInclude() {
        RuleSelectorPredicate include = new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.PATH, SelectorOperator.GLOB,
                List.of("**/*Controller.java"));
        assertThat(new RuleSelectorGroup("controller", List.of(include)).predicates())
                .containsExactly(include);
        assertThatThrownBy(() -> new RuleSelectorGroup("controller", List.of(
                new RuleSelectorPredicate(SelectorPolarity.EXCLUDE, SelectorField.SOURCE_SET,
                        SelectorOperator.IN, List.of("TEST")))))
                .hasMessageContaining("at least one INCLUDE");
        assertThatThrownBy(() -> new RuleSelectorGroup(" ", List.of(include)))
                .hasMessageContaining("group key");
    }
}
