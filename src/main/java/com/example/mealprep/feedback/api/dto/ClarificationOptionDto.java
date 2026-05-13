package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.spi.Destination;

/**
 * One option in the classifier's shortlist for a clarification question. The list is persisted as
 * JSON inside {@code feedback_clarification_queries.classifier_options_json} and deserialised by
 * {@code ClarificationQueryMapper}.
 */
public record ClarificationOptionDto(
    Destination destination, String snippet, String classifierJustification) {}
