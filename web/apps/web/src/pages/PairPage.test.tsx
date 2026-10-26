import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { PairPage } from "./PairPage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return { ...actual, api: { confirmPairing: vi.fn() } };
});

const { api } = await import("../api");

describe("PairPage", () => {
  it("fills in the code from the TV's QR link and confirms it", async () => {
    const user = userEvent.setup();
    vi.mocked(api.confirmPairing).mockResolvedValue({ deviceName: "TV" });
    render(
      <MemoryRouter initialEntries={["/pair?code=wxyz-2345"]}>
        <PairPage />
      </MemoryRouter>
    );

    expect(screen.getByLabelText("Code shown on the TV")).toHaveValue("WXYZ-2345");
    await user.click(screen.getByRole("button", { name: "Sign in TV" }));

    await waitFor(() => expect(api.confirmPairing).toHaveBeenCalledWith("WXYZ-2345", undefined));
    expect(await screen.findByText("TV is signed in.")).toBeInTheDocument();
  });
});
