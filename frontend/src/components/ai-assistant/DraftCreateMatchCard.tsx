"use client";

import { useState } from "react";
import { CalendarDays, Clock3, Sparkles, Users, WalletCards } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { DraftCreateMatchResponse } from "@/types/aiChat";

interface DraftCreateMatchCardProps {
  draft: DraftCreateMatchResponse;
  onSubmit?: (message: string) => void | Promise<void>;
  disabled?: boolean;
}

type MatchingType = "INDIVIDUAL" | "TEAM_VS_TEAM";
type SkillLevel = "BEGINNER" | "INTERMEDIATE" | "ADVANCED";

const skillOptions: Array<{ value: SkillLevel; label: string }> = [
  { value: "BEGINNER", label: "Người mới" },
  { value: "INTERMEDIATE", label: "Trung bình" },
  { value: "ADVANCED", label: "Nâng cao" },
];

const skillLabels: Record<SkillLevel, string> = {
  BEGINNER: "người mới",
  INTERMEDIATE: "trung bình",
  ADVANCED: "nâng cao",
};

function formatDate(value: string): string {
  const [year, month, day] = value.split("-");
  return year && month && day ? `${day}/${month}/${year}` : value;
}

function formatTime(value: string): string {
  return value?.slice(0, 5) ?? "";
}

export function DraftCreateMatchCard({ draft, onSubmit, disabled = false }: DraftCreateMatchCardProps) {
  const [matchingType, setMatchingType] = useState<MatchingType | null>(
    draft.matchingType ?? null
  );
  const [skillLevel, setSkillLevel] = useState<SkillLevel | null>(draft.skillLevel ?? null);
  const [splitPrice, setSplitPrice] = useState<boolean | null>(draft.splitPrice ?? null);
  const [maxPlayers, setMaxPlayers] = useState(
    draft.maxPlayers != null ? String(draft.maxPlayers) : ""
  );
  const [pricePerPlayer, setPricePerPlayer] = useState(
    draft.pricePerPlayer != null ? String(draft.pricePerPlayer) : ""
  );
  const [error, setError] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const inactive = disabled || submitted;

  const selectClass = (selected: boolean) =>
    `h-auto min-h-9 flex-1 whitespace-normal px-2 py-2 text-xs ${
      selected
        ? "border-primary bg-primary/10 text-primary hover:bg-primary/15"
        : "border-border bg-background text-foreground hover:bg-muted"
    }`;

  const submit = () => {
    if (!matchingType || !skillLevel || splitPrice == null) {
      setError("Vui lòng chọn đầy đủ hình thức, trình độ và cách chia tiền.");
      return;
    }

    const players = Number(maxPlayers);
    if (matchingType === "INDIVIDUAL" && (!Number.isInteger(players) || players < 2 || players > 50)) {
      setError("Số người tối đa phải từ 2 đến 50.");
      return;
    }

    const price = Number(pricePerPlayer);
    if (splitPrice && (!Number.isFinite(price) || price <= 0)) {
      setError("Vui lòng nhập số tiền mỗi người lớn hơn 0.");
      return;
    }

    setError("");
    const formatText = matchingType === "INDIVIDUAL"
      ? `Ghép lẻ, tối đa ${players} người`
      : "Cáp kèo đội vs đội";
    const splitText = splitPrice
      ? `chia tiền ${Math.round(price)} đồng mỗi người`
      : "không chia tiền";
    setSubmitted(true);
    void onSubmit?.(`Xác nhận tạo kèo: ${formatText}, trình độ ${skillLabels[skillLevel]}, ${splitText}`);
  };

  const cancel = () => {
    setSubmitted(true);
    void onSubmit?.("hủy tạo kèo");
  };

  return (
    <div className="w-full rounded-xl border border-primary/25 bg-background p-3 shadow-sm">
      <div className="mb-3 flex items-start gap-2">
        <div className="rounded-full bg-primary/10 p-1.5">
          <Sparkles className="h-4 w-4 text-primary" />
        </div>
        <div className="min-w-0">
          <p className="text-xs font-semibold text-foreground">Thông tin tạo kèo</p>
          <p className="mt-0.5 break-words text-sm font-bold text-primary">{draft.defaultTitle}</p>
          <p className="mt-0.5 text-[10px] text-muted-foreground">Tiêu đề được tạo tự động theo môn và sân</p>
        </div>
      </div>

      <div className="mb-3 rounded-lg bg-muted/50 p-2 text-[11px] text-muted-foreground">
        <p className="font-medium text-foreground">{draft.stadiumName} · {draft.sportName}</p>
        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
          <span className="flex items-center gap-1"><CalendarDays className="h-3 w-3" />{formatDate(draft.playDate)}</span>
          <span className="flex items-center gap-1"><Clock3 className="h-3 w-3" />{formatTime(draft.startTime)}–{formatTime(draft.endTime)}</span>
        </div>
      </div>

      <div className="space-y-3">
        <fieldset disabled={inactive}>
          <legend className="mb-1.5 flex items-center gap-1 text-xs font-semibold">
            <Users className="h-3.5 w-3.5 text-primary" /> Hình thức
          </legend>
          <div className="flex gap-2">
            <Button type="button" variant="outline" className={selectClass(matchingType === "INDIVIDUAL")} onClick={() => setMatchingType("INDIVIDUAL")}>Ghép người chơi lẻ</Button>
            <Button type="button" variant="outline" className={selectClass(matchingType === "TEAM_VS_TEAM")} onClick={() => setMatchingType("TEAM_VS_TEAM")}>Đội vs đội</Button>
          </div>
        </fieldset>

        {matchingType === "INDIVIDUAL" && (
          <label className="block text-xs font-semibold">
            Số người tối đa
            <Input type="number" min={2} max={50} value={maxPlayers} onChange={(event) => setMaxPlayers(event.target.value)} disabled={inactive} placeholder="Ví dụ: 10" className="mt-1 h-9 text-xs" />
          </label>
        )}

        <fieldset disabled={inactive}>
          <legend className="mb-1.5 text-xs font-semibold">Trình độ</legend>
          <div className="grid grid-cols-3 gap-1.5">
            {skillOptions.map((option) => (
              <Button key={option.value} type="button" variant="outline" className={selectClass(skillLevel === option.value)} onClick={() => setSkillLevel(option.value)}>{option.label}</Button>
            ))}
          </div>
        </fieldset>

        <fieldset disabled={inactive}>
          <legend className="mb-1.5 flex items-center gap-1 text-xs font-semibold">
            <WalletCards className="h-3.5 w-3.5 text-primary" /> Chia tiền sân
          </legend>
          <div className="flex gap-2">
            <Button type="button" variant="outline" className={selectClass(splitPrice === false)} onClick={() => setSplitPrice(false)}>Không chia</Button>
            <Button type="button" variant="outline" className={selectClass(splitPrice === true)} onClick={() => setSplitPrice(true)}>Có chia</Button>
          </div>
        </fieldset>

        {splitPrice === true && (
          <label className="block text-xs font-semibold">
            Tiền mỗi người (đồng)
            <Input type="number" min={1} step={1000} value={pricePerPlayer} onChange={(event) => setPricePerPlayer(event.target.value)} disabled={inactive} placeholder="Ví dụ: 50000" className="mt-1 h-9 text-xs" />
          </label>
        )}

        {error && <p className="text-[11px] text-destructive">{error}</p>}

        <div className="flex gap-2 pt-1">
          <Button type="button" variant="outline" disabled={inactive} onClick={cancel} className="h-9 flex-1 text-xs">Hủy</Button>
          <Button type="button" disabled={inactive} onClick={submit} className="h-9 flex-[2] text-xs">Tạo kèo</Button>
        </div>
        {inactive && <p className="text-center text-[10px] text-muted-foreground">Form này đã hết hiệu lực.</p>}
      </div>
    </div>
  );
}
