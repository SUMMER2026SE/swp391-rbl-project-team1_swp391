"use client";

import { useRef, useEffect } from "react";
import { Header } from "@/components/layout/Header";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card } from "@/components/ui/card";
import { Avatar } from "@/components/ui/avatar";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { Bot, Send, Trash2 } from "lucide-react";
import { useAiChat } from "@/hooks/useAiChat";
import { ChatMessageItem } from "@/components/ai-assistant/ChatMessageItem";
import { SuggestionChips } from "@/components/ai-assistant/SuggestionChips";

function AIAssistantPage() {
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
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isSearching]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="h-screen flex flex-col bg-background">
      <Header />

      <div className="flex-1 container mx-auto px-4 py-6 overflow-hidden flex flex-col max-w-4xl">
        <div className="mb-4 text-center shrink-0">
          <div className="inline-flex items-center justify-center w-12 h-12 bg-primary/10 rounded-full mb-2">
            <Bot className="h-6 w-6 text-primary" />
          </div>
          <h1 className="text-xl font-bold">Trợ lý AI đặt sân</h1>
          <p className="text-xs text-muted-foreground">
            Hỏi tôi bất cứ điều gì về sân thể thao, giá cả, lịch trống và kèo ghép
          </p>
        </div>

        <Card
          className="chat-widget-container flex-1 flex flex-col overflow-hidden border-border/80 shadow-sm rounded-xl"
          style={{ viewTransitionName: "chat-widget-container" }}
        >
          {/* Header Card chứa nút Xóa Lịch sử */}
          <div className="px-6 py-3 border-b border-border/60 flex justify-between items-center bg-muted/10 shrink-0">
            <div className="flex items-center gap-2">
              <Bot className="h-4 w-4 text-primary animate-pulse" />
              <span className="text-xs font-semibold text-gray-700">SportHub Assistant</span>
            </div>
            {messages.length > 1 && (
              <Button
                variant="ghost"
                size="sm"
                onClick={handleClearHistory}
                className="text-xs text-muted-foreground hover:text-destructive hover:bg-destructive/5 gap-1.5 h-8 px-2.5 rounded-lg"
              >
                <Trash2 className="h-3.5 w-3.5" />
                Xóa lịch sử
              </Button>
            )}
          </div>

          <ScrollArea className="flex-1 min-h-0 p-6">
            <div className="space-y-6">
              {messages.map((msg, idx) => (
                <ChatMessageItem
                  key={msg.id}
                  msg={msg}
                  isLatest={idx === messages.length - 1}
                  onSend={handleSend}
                  isSending={isSearching}
                />
              ))}

              {/* Hiện Suggestion Chips khi chỉ có 1 lời chào */}
              {messages.length === 1 && !isSearching && (
                <SuggestionChips onSelect={(text) => handleSend(text)} />
              )}

              {/* Skeleton loading bubble */}
              {isSearching && (
                <div className="flex gap-3">
                  <Avatar className="h-10 w-10 flex-shrink-0">
                    <div className="w-full h-full bg-primary/10 flex items-center justify-center">
                      <Bot className="h-6 w-6 text-primary" />
                    </div>
                  </Avatar>
                  <div className="flex-1 flex flex-col gap-2">
                    <div className="inline-block bg-muted rounded-2xl rounded-tl-sm px-4 py-3 max-w-[120px]">
                      <div className="flex space-x-1.5 items-center justify-center h-4">
                        <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                        <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                        <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                      </div>
                    </div>
                    <div className="w-full max-w-sm space-y-3 mt-1">
                      <Skeleton className="h-32 w-full rounded-xl bg-muted/65" />
                    </div>
                  </div>
                </div>
              )}
              <div ref={chatEndRef} />
            </div>
          </ScrollArea>

          <div className="p-4 border-t border-border/60 bg-muted/20 shrink-0">
            <div className="flex gap-2 max-w-3xl mx-auto">
              <Input
                placeholder="Hỏi tôi về sân, giá, lịch trống, kèo ghép..."
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                onKeyDown={handleKeyDown}
                disabled={isSearching}
                className="rounded-xl border-border/80 focus-visible:ring-primary h-10"
              />
              <Button onClick={() => handleSend()} disabled={!message.trim() || isSearching} className="rounded-xl h-10 px-4">
                <Send className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </Card>
      </div>
    </div>
  );
}

export default AIAssistantPage;
