"use client";

import React from "react";
import { SubPlanResponse } from "@/types/aiChat";
import { CheckCircle2, PartyPopper } from "lucide-react";

interface PlanCompletionCardProps {
  subPlan: SubPlanResponse;
}

export function PlanCompletionCard({ subPlan }: PlanCompletionCardProps) {
  if (!subPlan || !subPlan.steps) return null;

  return (
    <div className="bg-gradient-to-br from-green-50 to-emerald-50 border border-green-200 rounded-xl p-4 shadow-sm w-full max-w-sm">
      <div className="flex items-center gap-2 mb-3">
        <PartyPopper className="w-5 h-5 text-green-600" />
        <h3 className="font-semibold text-green-800 text-sm">
          Hoàn thành tất cả các bước!
        </h3>
      </div>

      <div className="space-y-2">
        {subPlan.steps.map((step, index) => (
          <div key={index} className="flex items-start gap-2 text-sm">
            <CheckCircle2 className="w-4 h-4 mt-0.5 shrink-0 text-green-500" />
            <span className="text-green-800 font-medium">{step.description}</span>
          </div>
        ))}
      </div>

      <p className="mt-3 text-xs text-green-600 bg-green-100 rounded-md px-3 py-2">
        Tất cả {subPlan.steps.length} bước đã được thực hiện thành công. Kiểm tra lịch đặt sân của bạn để biết thêm chi tiết.
      </p>
    </div>
  );
}
