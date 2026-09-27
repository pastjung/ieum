import { extractActionToken } from "@/services/actionToken";

describe("메일 링크의 action token 추출", () => {
  it("fragment에서 토큰을 읽는다", () => {
    expect(extractActionToken("ieum://auth/verify-email#token=abc.def.ghi")).toBe("abc.def.ghi");
  });

  it("퍼센트 인코딩을 되돌린다", () => {
    // 백엔드가 x-www-form-urlencoded 규칙으로 인코딩해 보낸다
    expect(extractActionToken("ieum://auth/verify-email#token=a%2Bb%2Fc%3D")).toBe("a+b/c=");
  });

  it("다른 fragment 항목이 섞여 있어도 찾는다", () => {
    expect(extractActionToken("ieum://x#state=1&token=abc&next=2")).toBe("abc");
  });

  it("query가 아니라 fragment만 본다", () => {
    // 토큰을 query로 옮기면 서버 접근 로그에 남는다. 그 형태는 받지 않는다
    expect(extractActionToken("ieum://auth/verify-email?token=abc")).toBeNull();
  });

  it("앱을 딥링크로 열지 않았으면 null이다", () => {
    expect(extractActionToken(null)).toBeNull();
    expect(extractActionToken(undefined)).toBeNull();
    expect(extractActionToken("ieum://auth/verify-email")).toBeNull();
  });

  it("빈 토큰은 없는 것으로 다룬다", () => {
    expect(extractActionToken("ieum://x#token=")).toBeNull();
  });

  it("token으로 시작하는 다른 이름에 속지 않는다", () => {
    expect(extractActionToken("ieum://x#tokenType=Bearer")).toBeNull();
  });

  it("인코딩이 깨진 링크는 null이다", () => {
    // 원문을 그대로 보내면 서버에서 400이 날 뿐이다. 화면이 재요청을 안내하게 둔다
    expect(extractActionToken("ieum://x#token=%E0%A4%A")).toBeNull();
  });
});
