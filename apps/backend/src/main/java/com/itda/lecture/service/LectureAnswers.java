package com.itda.lecture.service;

import java.util.List;

/**
 * @param answers 제출한 학습자만, 제출 시각 오름차순
 */
public record LectureAnswers(long problemId, List<LearnerAnswer> answers) {}
