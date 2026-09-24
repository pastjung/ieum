import model from "../../assets/nl/itda_nl_model.json";

/**
 * 트레이닝 산출물 — `ai/scripts/train_nl.py`가 여기로 바로 써 준다.
 *
 * 번들에 함께 실리므로 네트워크 없이 첫 실행부터 쓴다. 모양 검사는 `NlModel.parse`가 하니
 * 여기서는 `unknown`으로만 넘긴다(키가 수천 개라 타입을 세울 이유가 없다).
 */
export const nlModelJson: unknown = model;
