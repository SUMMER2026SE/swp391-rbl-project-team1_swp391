"use client";

import { useState, useRef, useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card } from "@/components/ui/card";
import { Avatar } from "@/components/ui/avatar";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { Bot, Send, Trash2, X, MessageSquare, Maximize2 } from "lucide-react";
import { useAiChat } from "@/hooks/useAiChat";
import { ChatMessageItem } from "@/components/ai-assistant/ChatMessageItem";
import { SuggestionChips } from "@/components/ai-assistant/SuggestionChips";

const HIDDEN_PREFIXES = [
  "/owner",
  "/admin",
  "/ai-assistant",
  "/auth",
  "/payments",
  "/login",
  "/register"
];

function isHiddenRoute(pathname: string): boolean {
  return HIDDEN_PREFIXES.some((prefix) => pathname.startsWith(prefix));
}

export function ChatWidget() {
  const pathname = usePathname();
  const router = useRouter();
  const [isOpen, setIsOpen] = useState(false);

  const {
    message,
    setMessage,
    isSearching,
    messages,
    handleSend,
    handleClearHistory,
  } = useAiChat();

  const chatEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (isOpen) {
      chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages, isSearching, isOpen]);

  // Handle expand to full page with view transition
  const handleExpand = () => {
    if (document.startViewTransition) {
      document.startViewTransition(() => {
        router.push("/ai-assistant");
      });
    } else {
      router.push("/ai-assistant"); // Fallback for browsers without View Transitions API
    }
  };

  // Không hiển thị widget trên các trang thuộc danh sách loại trừ
  if (isHiddenRoute(pathname)) {
    return null;
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="fixed bottom-6 right-6 z-50 flex flex-col items-end pointer-events-none">
      {/* Panel Chat Widget - View Transition container */}
      <Card
        className={`chat-widget-container w-[360px] xs:w-[380px] max-w-[calc(100vw-32px)] h-[520px] max-h-[calc(100vh-120px)] flex flex-col overflow-hidden border-border/80 shadow-2xl rounded-2xl bg-background transition-all duration-300 ease-out origin-bottom-right mb-4 ${
          isOpen
            ? "opacity-100 scale-100 translate-y-0 pointer-events-auto"
            : "opacity-0 scale-95 translate-y-4 pointer-events-none"
        }`}
        style={{ viewTransitionName: "chat-widget-container" }}
      >
        {/* Header Widget */}
        <div className="px-4 py-3 border-b border-border/60 flex justify-between items-center bg-muted/10 shrink-0">
          <div className="flex items-center gap-2">
            <div className="w-2.5 h-2.5 bg-emerald-500 rounded-full animate-ping absolute" />
            <div className="w-2.5 h-2.5 bg-emerald-500 rounded-full" />
            <span className="text-xs font-bold text-gray-700">Trợ lý AI SportHub</span>
          </div>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={handleExpand}
              className="h-8 w-8 text-muted-foreground hover:bg-muted rounded-lg"
              title="Mở rộng trang"
            >
              <Maximize2 className="h-4 w-4" />
            </Button>
            {messages.length > 1 && (
              <Button
                variant="ghost"
                size="icon"
                onClick={handleClearHistory}
                className="h-8 w-8 text-muted-foreground hover:text-destructive hover:bg-destructive/5 rounded-lg"
                title="Xóa lịch sử"
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            )}
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setIsOpen(false)}
              className="h-8 w-8 text-muted-foreground hover:bg-muted rounded-lg"
            >
              <X className="h-4 w-4" />
            </Button>
          </div>
        </div>

        {/* ScrollArea hiển thị hội thoại */}
        <ScrollArea className="flex-1 min-h-0 p-4">
          <div className="space-y-4">
            {messages.map((msg, idx) => (
              <ChatMessageItem
                key={msg.id}
                msg={msg}
                isLatest={idx === messages.length - 1}
                onSend={handleSend}
              />
            ))}

            {/* Chips gợi ý câu hỏi khi chỉ có tin chào mừng */}
            {messages.length === 1 && !isSearching && (
              <SuggestionChips onSelect={(text) => handleSend(text)} />
            )}

            {/* Skeleton loading bubble */}
            {isSearching && (
              <div className="flex gap-2.5">
                <Avatar className="h-9 w-9 flex-shrink-0">
                  <div className="w-full h-full bg-primary/10 flex items-center justify-center">
                    <Bot className="h-5 w-5 text-primary" />
                  </div>
                </Avatar>
                <div className="flex-1 flex flex-col gap-2">
                  <div className="inline-block bg-muted rounded-2xl rounded-tl-sm px-3.5 py-2.5 max-w-[100px]">
                    <div className="flex space-x-1 items-center justify-center h-3">
                      <div className="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                      <div className="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                      <div className="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                    </div>
                  </div>
                  <div className="w-full max-w-[240px] space-y-2 mt-1">
                    <Skeleton className="h-24 w-full rounded-xl bg-muted/65" />
                  </div>
                </div>
              </div>
            )}
            <div ref={chatEndRef} />
          </div>
        </ScrollArea>

        {/* Input Bar */}
        <div className="p-3 border-t border-border/60 bg-muted/20 shrink-0">
          <div className="flex gap-2">
            <Input
              placeholder="Nhập câu hỏi của bạn..."
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={isSearching}
              className="rounded-xl border-border/80 focus-visible:ring-primary h-9 text-xs"
            />
            <Button onClick={() => handleSend()} disabled={!message.trim() || isSearching} className="rounded-xl h-9 w-9 p-0 shrink-0">
              <Send className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </Card>

      {/* Nút bong bóng nổi để bật/tắt widget */}
      <Button
        onClick={() => setIsOpen(!isOpen)}
        className={`h-14 w-14 rounded-full shadow-2xl hover:scale-105 active:scale-95 transition-all duration-200 border-0 flex items-center justify-center text-white pointer-events-auto ${
          isOpen ? "bg-slate-700 hover:bg-slate-800" : "bg-primary hover:bg-primary/95"
        }`}
      >
        {isOpen ? (
          <X className="h-6 w-6" />
        ) : (
          <MessageSquare className="h-6 w-6" />
        )}
      </Button>
    </div>
  );
}
