package com.itda.problem.service;

import com.itda.global.FastApiClient;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.dto.BrailleDictionaryResponse;
import com.itda.problem.dto.BrailleLookupFastApiRequest;
import com.itda.problem.dto.BrailleLookupFastApiResponse;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 점자 사전. 등록된 문제에 같은 글자가 있으면 그 정답 칸을 그대로 돌려주고, 없으면 AI 규칙 점역기로 바꿔 돌려준다.
 *
 * <p>바꾼 결과는 문제로 저장하지 않는다 — 사전은 아무 글자나 찾아볼 수 있어야 하고, 그 글자가 학습 문제 풀에 섞이면 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrailleDictionaryService {

  static final int MAX_LENGTH = 40;
  static final String SOURCE_PROBLEM = "PROBLEM";
  static final String SOURCE_CONVERTED = "CONVERTED";
  private static final int MAX_PATTERN = 63;
  private static final char BRAILLE_BASE = '\u2800';

  private final ProblemRepository problemRepository;
  private final ProblemCellRepository problemCellRepository;
  private final FastApiClient fastApiClient;

  @Transactional(readOnly = true)
  public BrailleDictionaryResponse lookup(String rawWord) {
    String word = normalize(rawWord);
    Optional<Problem> problem = problemRepository.findByTargetText(word);
    if (problem.isPresent()) {
      List<ProblemCell> cells =
          problemCellRepository.findByProblemIdOrderByCellIndexAsc(problem.get().getId());
      if (!cells.isEmpty()) {
        return fromProblem(word, cells);
      }
    }
    return convert(word);
  }

  private static String normalize(String rawWord) {
    if (rawWord == null) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    // 여러 칸 공백·앞뒤 공백을 한 칸으로 — "학교  가방"과 "학교 가방"은 같은 검색이다
    String word = rawWord.strip().replaceAll("\\s+", " ");
    if (word.isEmpty() || word.length() > MAX_LENGTH) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    return word;
  }

  private static BrailleDictionaryResponse fromProblem(String word, List<ProblemCell> cells) {
    List<BrailleDictionaryResponse.Cell> out =
        cells.stream()
            .map(
                cell ->
                    new BrailleDictionaryResponse.Cell(
                        cell.getCellIndex(), cell.getExpectedPattern(), null, null))
            .toList();
    return new BrailleDictionaryResponse(word, SOURCE_PROBLEM, toBraille(out), out);
  }

  private BrailleDictionaryResponse convert(String word) {
    BrailleLookupFastApiResponse response;
    try {
      response = fastApiClient.lookupBraille(new BrailleLookupFastApiRequest(word));
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().value() == HttpStatus.UNPROCESSABLE_CONTENT.value()) {
        throw new BusinessException(ErrorCode.BRAILLE_TEXT_UNSUPPORTED);
      }
      log.warn("FastAPI braille lookup failed: {}", exception.getStatusCode());
      throw new BusinessException(ErrorCode.BRAILLE_CONVERSION_UNAVAILABLE);
    } catch (RestClientException exception) {
      log.warn("FastAPI braille lookup failed", exception);
      throw new BusinessException(ErrorCode.BRAILLE_CONVERSION_UNAVAILABLE);
    }
    if (response == null || response.cells() == null || response.cells().isEmpty()) {
      throw new BusinessException(ErrorCode.BRAILLE_CONVERSION_UNAVAILABLE);
    }
    List<BrailleDictionaryResponse.Cell> out =
        response.cells().stream()
            .map(
                cell -> {
                  if (cell == null || cell.pattern() < 0 || cell.pattern() > MAX_PATTERN) {
                    throw new BusinessException(ErrorCode.BRAILLE_CONVERSION_UNAVAILABLE);
                  }
                  return new BrailleDictionaryResponse.Cell(
                      cell.cellIndex(), cell.pattern(), cell.learningSkillId(), cell.label());
                })
            .toList();
    return new BrailleDictionaryResponse(word, SOURCE_CONVERTED, toBraille(out), out);
  }

  private static String toBraille(List<BrailleDictionaryResponse.Cell> cells) {
    StringBuilder builder = new StringBuilder(cells.size());
    for (BrailleDictionaryResponse.Cell cell : cells) {
      builder.append((char) (BRAILLE_BASE + cell.pattern()));
    }
    return builder.toString();
  }
}
