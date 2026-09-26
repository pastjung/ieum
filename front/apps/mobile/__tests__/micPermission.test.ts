import { AudioModule } from "expo-audio";
import { ensureMicPermission } from "@/services/micPermission";

jest.mock("expo-audio", () => ({
  AudioModule: { requestRecordingPermissionsAsync: jest.fn() },
}));

const request = AudioModule.requestRecordingPermissionsAsync as unknown as jest.Mock;

beforeEach(() => request.mockReset());

describe("ensureMicPermission", () => {
  it("허용하면 true", async () => {
    request.mockResolvedValue({ granted: true, status: "granted", canAskAgain: true, expires: "never" });
    await expect(ensureMicPermission()).resolves.toBe(true);
  });

  it("거부하면 false", async () => {
    request.mockResolvedValue({ granted: false, status: "denied", canAskAgain: false, expires: "never" });
    await expect(ensureMicPermission()).resolves.toBe(false);
  });

  it("요청 자체가 실패해도 false", async () => {
    request.mockRejectedValue(new Error("no activity"));
    await expect(ensureMicPermission()).resolves.toBe(false);
  });
});
