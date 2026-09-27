package com.itda.lecture.service;

import com.itda.lecture.domain.Lecture;

/**
 * 수업 목록 항목의 집계 결과 (API 명세 8.2절).
 *
 * @param problemCount 이 수업에 연결된 lecture_problems 수
 * @param joinedCount left_at이 NULL인 현재 참여자 수
 * @param participantCount 전체 참여자(participants) 수
 * @param correctRate LIVE 풀이의 정답률(0~100 반올림). LIVE 풀이가 없으면 null
 */
public record LectureListItem(
    Lecture lecture,
    int problemCount,
    int joinedCount,
    int participantCount,
    Integer correctRate) {}
