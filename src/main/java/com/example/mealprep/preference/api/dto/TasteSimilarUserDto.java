package com.example.mealprep.preference.api.dto;

import java.util.UUID;

/**
 * One result of a taste-vector similarity query: a user whose taste embedding is near the query
 * vector, with the cosine similarity in {@code [0,1]} ({@code 1.0} = identical direction, {@code
 * 0.0} = orthogonal/opposite after the {@code [-1,1] → [0,1]} remap). Derived from the pgvector
 * cosine <em>distance</em> ({@code <=>}, range {@code [0,2]}) as {@code similarity = 1 - distance /
 * 2} is NOT used; cosine distance here is {@code 1 - cosineSimilarity}, so {@code similarity = 1 -
 * distance} and the value is clamped to {@code [0,1]} defensively.
 */
public record TasteSimilarUserDto(UUID userId, double similarity) {}
