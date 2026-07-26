"use client";

import React from "react";
import { SubPlanResponse } from "@/types/aiChat";
import { CheckCircle2, Circle, Loader2, XCircle, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";

interface SubPlanProgressCardProps {
  subPlan: SubPlanResponse;
  onNextStep?: () => void;
  onCancel?: () => void;
  isLatestMessage?: boolean;
}

export function SubPlanProgressCard({
  subPlan,
  onNextStep,
  onCancel,
  isLatestMessage,
}: SubPlanProgressCardProps) {
  if (!subPlan || !subPlan.steps) return null;

  const completedCount = subPlan.steps.filter((s) => s.status === "COMPLETED").length;
  const total = subPlan.steps.length;

  return (
    <div className="bg-white border border-blue-100 rounded-xl p-4 shadow-sm w-full max-w-sm space-y-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="font-semibold text-gray-800 text-sm">Kế hoạch thực hiện</h3>
        <span className="text-xs text-gray-500 bg-gray-100 px-2 py-0.5 rounded-full">
          {completedCount}/{total} bước
        </span>
      </div>

      {/* Progress bar */}
      <div className="flex gap-1">
        {subPlan.steps.map((step, idx) => (
          <div
            key={idx}
            className={`h-1.5 flex-1 rounded-full transition-all duration-300 ${
              step.status === "COMPLETED"
                ? "bg-green-500"
                : step.status === "FAILED"
                ? "bg-red-400"
                : step.status === "IN_PROGRESS"
                ? "bg-blue-400 animate-pulse"
                : "bg-gray-200"
            }`}
          />
        ))}
      </div>

      {/* Steps list */}
      <div className="space-y-2.5">
        {subPlan.steps.map((step, index) => {
          const isCurrent = index === subPlan.currentStepIndex;

          let Icon = Circle;
          let iconColor = "text-gray-300";

          if (step.status === "COMPLETED") {
            Icon = CheckCircle2;
            iconColor = "text-green-500";
          } else if (step.status === "IN_PROGRESS") {
            Icon = Loader2;
            iconColor = "text-blue-500 animate-spin";
          } else if (step.status === "FAILED") {
            Icon = XCircle;
            iconColor = "text-red-500";
          } else if (step.status === "ROLLED_BACK") {
            Icon = XCircle;
            iconColor = "text-orange-400";
          } else if (isCurrent) {
            Icon = ArrowRight;
            iconColor = "text-blue-500";
          }

          return (
            <div
              key={index}
              className={`flex items-start gap-2 text-sm rounded-lg px-2 py-1.5 transition-colors ${
                isCurrent && step.status === "PENDING"
                  ? "bg-blue-50 border border-blue-100"
                  : ""
              }`}
            >
              <Icon className={`w-4 h-4 mt-0.5 shrink-0 ${iconColor}`} />
              <div className="flex-1 min-w-0">
                <span
                  className={`block leading-snug ${
                    step.status === "PENDING" && !isCurrent
                      ? "text-gray-400"
                      : step.status === "COMPLETED"
                      ? "text-gray-600 line-through"
                      : "text-gray-800 font-medium"
                  }`}
                >
                  {step.description}
                </span>
                {step.errorReason && (
                  <p className="text-red-500 text-xs mt-0.5">{step.errorReason}</p>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Action buttons */}
      {isLatestMessage &&
        (subPlan.status === "IN_PROGRESS" || subPlan.status === "PAUSED") && (
          <div className="flex gap-2 justify-end pt-1 border-t border-gray-100">
            <Button
              variant="outline"
              size="sm"
              className="text-red-600 border-red-200 hover:bg-red-50 h-7 text-xs"
              onClick={onCancel}
            >
              Hủy bỏ
            </Button>
            <Button
              variant="default"
              size="sm"
              className="bg-blue-600 hover:bg-blue-700 text-white h-7 text-xs"
              onClick={onNextStep}
            >
              {subPlan.status === "PAUSED" ? "Thử lại" : "Tiếp tục →"}
            </Button>
          </div>
        )}
    </div>
  );
}
