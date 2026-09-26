package com.itda.problem.service;

/**
 * @param created 이번 요청이 새로 연결했으면 true(201), 이미 연결돼 있었으면 false(200)
 */
public record RegisteredProblem(LinkedProblem problem, boolean created) {}
