package com.itda.problem.dto;

import com.itda.problem.domain.ErrorType;
import com.itda.problem.domain.UserSkillErrorCount;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ErrorCount(
    Long dot_missing,
    Long dot_extra,
    Long dot_mixed,
    Long mirror,
    Long vflip,
    Long rot180,
    Long shift_down,
    Long shift_up,
    Long jamo_confusion,
    Long cell_omitted,
    Long cell_inserted,
    Long transposition,
    Long abbr_expanded,
    Long abbr_overused,
    Long marker_missing,
    Long silent_onset_written) {

  public static ErrorCount from(List<UserSkillErrorCount> errorCounts) {

    Map<ErrorType, Long> countMap =
        errorCounts.stream()
            .collect(
                Collectors.toMap(
                    UserSkillErrorCount::getErrorType, UserSkillErrorCount::getErrorCount));

    return new ErrorCount(
        countMap.getOrDefault(ErrorType.DOT_MISSING, 0L),
        countMap.getOrDefault(ErrorType.DOT_EXTRA, 0L),
        countMap.getOrDefault(ErrorType.DOT_MIXED, 0L),
        countMap.getOrDefault(ErrorType.MIRROR, 0L),
        countMap.getOrDefault(ErrorType.VFLIP, 0L),
        countMap.getOrDefault(ErrorType.ROT180, 0L),
        countMap.getOrDefault(ErrorType.SHIFT_DOWN, 0L),
        countMap.getOrDefault(ErrorType.SHIFT_UP, 0L),
        countMap.getOrDefault(ErrorType.JAMO_CONFUSION, 0L),
        countMap.getOrDefault(ErrorType.CELL_OMITTED, 0L),
        countMap.getOrDefault(ErrorType.CELL_INSERTED, 0L),
        countMap.getOrDefault(ErrorType.TRANSPOSITION, 0L),
        countMap.getOrDefault(ErrorType.ABBR_EXPANDED, 0L),
        countMap.getOrDefault(ErrorType.ABBR_OVERUSED, 0L),
        countMap.getOrDefault(ErrorType.MARKER_MISSING, 0L),
        countMap.getOrDefault(ErrorType.SILENT_ONSET_WRITTEN, 0L));
  }
}
