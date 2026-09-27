package com.itda.lecture.dto;

import com.itda.lecture.service.LectureListItem;
import java.util.List;
import org.springframework.data.domain.Page;

/** docs/conventions/code/api.md 페이지네이션 형식. */
public record LectureListResponse(
    List<LectureListItemResponse> items, int page, int size, long totalElements, int totalPages) {

  public static LectureListResponse from(Page<LectureListItem> page) {
    return new LectureListResponse(
        page.getContent().stream().map(LectureListItemResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
