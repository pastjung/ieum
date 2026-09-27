package com.itda.lecture.service;

/**
 * @param created 이번 요청이 저장했으면 true(201), 같은 답이 이미 있었으면 false(200)
 */
public record SubmittedAnswer(LiveAnswer answer, boolean created) {}
