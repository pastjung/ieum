package com.itda.lecture.service;

/** 강사 답안 목록의 한 줄 — 제출한 학습자와 그 답안. */
public record LearnerAnswer(long userId, String username, LiveAnswer answer) {}
