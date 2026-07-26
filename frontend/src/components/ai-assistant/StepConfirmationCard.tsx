"use client";

import React from "react";
import { SubPlanResponse, PlanStepResponse } from "@/types/aiChat";
import { CheckCircle2, AlertCircle, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";

interface StepConfirmationCardProps {
  /** The just-completed step (index - 1) */
  completedStep: PlanStepResponse;
  /** The upcoming step (index) — null if this was the last */
  nextStep: PlanStepResponse | null;
  subPlan: SubPlanResponse;
  onContinue?: () => void;
  onCancel?: () => void;
  isLatestMessage?: boolean;
}

/**
 * Shown after a step finishes — displays its outcome and prompts user to
 * confirm continuing to the next step (or cancel the rest of the plan).
 */
export function StepConfirmationCard({
  completedStep,
  nextStep,
  subPlan,
  onContinue,
  onCancel,
  isLatestMessage,
}: StepConfirmationCardProps) {
  const hasFailed = completedStep.status === "FAILED";

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-4 shadow-sm w-full max-w-sm space-y-3">
      {/* Completed step summary */}
      <div className={`flex items-start gap-2 p-3 rounded-lg ${hasFailed ? "bg-red-50 border border-red-100" : "bg-green-50 border border-green-100"}`}>
        {hasFailed ? (
          <AlertCircle className="w-4 h-4 mt-0.5 shrink-0 text-red-500" />
        ) : (
          <CheckCircle2 className="w-4 h-4 mt-0.5 shrink-0 text-green-500" />
        )}
        <div>
          <p className={`text-sm font-medium ${hasFailed ? "text-red-800" : "text-green-800"}`}>
            Bước {completedStep.stepNumber}: {completedStep.description}
          </p>
          {completedStep.errorReason && (
            <p className="text-xs text-red-600 mt-1">{completedStep.errorReason}</p>
          )}
        </div>
      </div>

      {/* Next step preview */}
      {nextStep && !hasFailed && (
        <div className="flex items-center gap-2 text-sm text-gray-600">
          <ArrowRight className="w-4 h-4 shrink-0 text-blue-500" />
          <span>
            Tiếp theo: <span className="font-medium text-gray-800">{nextStep.description}</span>
          </span>
        </div>
      )}

      {/* Progress tracker */}
      <div className="flex gap-1">
        {subPlan.steps.map((step, idx) => (
          <div
            key={idx}
            className={`h-1.5 flex-1 rounded-full transition-all ${
              step.status === "COMPLETED"
                ? "bg-green-500"
                : step.status === "FAILED"
                ? "bg-red-400"
                : idx === subPlan.currentStepIndex
                ? "bg-blue-400"
                : "bg-gray-200"
            }`}
          />
        ))}
      </div>

      {/* Action buttons — only visible on the latest message */}
      {isLatestMessage && (
        <div className="flex gap-2 justify-end pt-1">
          <Button
            variant="outline"
            size="sm"
            className="text-red-600 border-red-200 hover:bg-red-50"
            onClick={onCancel}
          >
            Hủy bỏ
          </Button>
          {!hasFailed && nextStep && (
            <Button
              variant="default"
              size="sm"
              className="bg-blue-600 hover:bg-blue-700 text-white"
              onClick={onContinue}
            >
              Tiếp tục →
            </Button>
          )}
          {hasFailed && (
            <Button
              variant="default"
              size="sm"
              className="bg-amber-500 hover:bg-amber-600 text-white"
              onClick={onContinue}
            >
              Thử lại
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
