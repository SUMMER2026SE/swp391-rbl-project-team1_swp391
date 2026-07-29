'use client'

import { useState, useEffect, useRef, useCallback } from "react"
import { useSession } from "next-auth/react"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { Header } from "@/components/layout/Header"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Separator } from "@/components/ui/separator"
import {
  Send, MessageSquare, Loader2, Search, Users, ArrowLeft, LogOut, Trash2,
  Smile, Reply, Pin, X, MoreHorizontal, MoreVertical, CheckCheck, EyeOff, Edit3, Ban
} from "lucide-react"
import {
  DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator
} from "@/components/ui/dropdown-menu"
import { Popover, PopoverTrigger, PopoverContent } from "@/components/ui/popover"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog"
import { toast } from "sonner"
import { useConfirm } from "@/hooks/useConfirm"
import { ConfirmDialog } from "@/components/common/ConfirmDialog"
import {
  getConversations, getMessages, sendMessage, markConversationAsRead,
  getUnreadCount, searchUsers, renameGroupChat, blockUser, unblockUser,
  leaveGroupChat, deleteConversation, type ConversationDto, type ChatMessageDto
} from "@/lib/chat-api"
import { getVenueDetail } from "@/lib/api/venue"
import { useChatWebSocket, type TypingEvent, type BlockEvent } from "@/hooks/useChatWebSocket"

function formatRelativeTime(dateStr: string | null | undefined): string {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  if (diffMins < 1) return 'vừa xong'
  if (diffMins < 60) return `${diffMins} phút`
  if (diffMins < 24 * 60) return `${Math.floor(diffMins / 60)} giờ`
  if (diffMins < 7 * 24 * 60) return `${Math.floor(diffMins / (24 * 60))} ngày`
  return d.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })
}

function formatMessagePreview(content: string | undefined | null): string {
  if (!content) return ''
  if (content.startsWith('{"action"')) {
    try {
      const parsed = JSON.parse(content)
      if (parsed.action === 'RECALL') return 'Tin nhắn đã thu hồi'
      if (parsed.action === 'REACT') return 'Đã thả cảm xúc'
      if (parsed.action === 'REACT_REMOVE') return 'Đã gỡ cảm xúc'
      if (parsed.action === 'PIN') return 'Đã ghim tin nhắn'
      if (parsed.action === 'UNPIN') return 'Đã bỏ ghim tin nhắn'
    } catch {}
  }
  const replyRegex = /^\[REPLY:.*?\]([\s\S]*?)\[\/REPLY\]([\s\S]*)$/
  const match = content.match(replyRegex)
  if (match) return match[2].trim()
  
  const oldReplyRegex = /^> Đang trả lời (.*?):\n> ([\s\S]*?)\n\n([\s\S]*)$/
  const oldMatch = content.match(oldReplyRegex)
  if (oldMatch) return oldMatch[3].trim()
  
  return content
}

function ChatPage() {
  const { data: session } = useSession()
  const router = useRouter()
  const currentUserId = (session?.user as { userId?: number })?.userId
  const currentUserName = session?.user?.name || 'Bạn'
  const queryClient = useQueryClient()

  const handleContextCardClick = (context: any) => {
    const isOwner = (session?.user as any)?.roleName === 'Owner'

    if (context.action === 'stadium_referral') {
      if (isOwner) {
        const query = new URLSearchParams()
        const isComplexOnly = context.nodeType === 'COMPLEX' || (context.complexId && context.stadiumId === context.complexId)
        if (isComplexOnly) {
          if (context.complexId) query.set('complexId', String(context.complexId))
        } else {
          if (context.complexId) query.set('complexId', String(context.complexId))
          if (context.facilityId) query.set('facilityId', String(context.facilityId))
          if (context.stadiumId) query.set('stadiumId', String(context.stadiumId))
        }
        router.push(`/owner/venues?${query.toString()}`)
      } else {
        if (context.nodeType === 'COMPLEX' && context.complexId) {
          router.push(`/complexes/${context.complexId}`)
        } else if (context.stadiumId && context.nodeType !== 'COMPLEX') {
          router.push(`/venues/${context.stadiumId}`)
        } else if (context.complexId) {
          router.push(`/complexes/${context.complexId}`)
        }
      }
    } else if (context.action === 'booking_referral') {
      if (isOwner) {
        router.push(`/owner/bookings?bookingId=${context.bookingId}`)
      } else {
        router.push(`/booking/${context.bookingId}`)
      }
    } else if (context.action === 'match_referral' && context.matchId) {
      router.push(`/community`)
    }
  }

  // ── State ──────────────────────────────────────────────────
  const [conversations, setConversations] = useState<ConversationDto[]>([])
  const [selectedConv, setSelectedConv] = useState<ConversationDto | null>(null)
  const [messages, setMessages] = useState<ChatMessageDto[]>([])
  const [recalledMessages, setRecalledMessages] = useState<Set<number>>(new Set())
  const [reactions, setReactions] = useState<Record<number, string>>({})
  const [venueCache, setVenueCache] = useState<Record<number, { complexId?: number; complexName?: string; facilityId?: number; facilityName?: string; stadiumName?: string; nodeType?: string }>>({})

  // Auto-fetch 3-level venue info for context cards missing complexName (e.g. legacy/old messages in DB)
  useEffect(() => {
    if (!messages || messages.length === 0) return
    const missingIds = new Set<number>()
    messages.forEach(msg => {
      if (msg.messageType === 'SYSTEM') {
        try {
          const ctx = JSON.parse(msg.content)
          const sId = ctx.stadiumId || ctx.venueId
          if (sId && !ctx.complexName && !venueCache[sId]) {
            missingIds.add(sId)
          }
        } catch {}
      }
    })
    if (missingIds.size > 0) {
      missingIds.forEach(id => {
        getVenueDetail(id)
          .then(v => {
            if (v) {
              setVenueCache(prev => ({
                ...prev,
                [id]: {
                  complexId: v.complexId,
                  complexName: v.complexName,
                  facilityId: v.parentStadiumId,
                  facilityName: v.facilityName,
                  stadiumName: v.stadiumName,
                  nodeType: v.nodeType
                }
              }))
            }
          })
          .catch(() => {})
      })
    }
  }, [messages])
  const [replyingTo, setReplyingTo] = useState<ChatMessageDto | null>(null)
  const [pinnedMessage, setPinnedMessage] = useState<{messageId: number, content: string} | null>(null)
  const [forwardingMessage, setForwardingMessage] = useState<ChatMessageDto | null>(null)
  const [forwardSearch, setForwardSearch] = useState("")
  const [sentForwards, setSentForwards] = useState<Set<number>>(new Set())
  const [messageInput, setMessageInput] = useState("")
  const [searchQuery, setSearchQuery] = useState("")
  const [searchResults, setSearchResults] = useState<ConversationDto[]>([])
  const [isSearching, setIsSearching] = useState(false)
  const [unreadOverrides, setUnreadOverrides] = useState<Record<number, boolean>>({})

  useEffect(() => {
    if (currentUserId) {
      try {
        const stored = localStorage.getItem(`chat_unread_overrides_${currentUserId}`)
        if (stored) setUnreadOverrides(JSON.parse(stored))
      } catch {}
    }
  }, [currentUserId])

  const toggleUnreadOverride = useCallback((convId: number, value: boolean) => {
    setUnreadOverrides(prev => {
      const next = { ...prev }
      if (value) next[convId] = true
      else delete next[convId]
      if (currentUserId) localStorage.setItem(`chat_unread_overrides_${currentUserId}`, JSON.stringify(next))
      return next
    })
  }, [currentUserId])

  const [loadingMessages, setLoadingMessages] = useState(false)
  const [sendingMessage, setSendingMessage] = useState(false)
  const [totalUnread, setTotalUnread] = useState(0)
  const [typingUsers, setTypingUsers] = useState<Map<number, string>>(new Map())
  const [showMobileChat, setShowMobileChat] = useState(false)

  const confirmState = useConfirm()
  const [renameModalOpen, setRenameModalOpen] = useState(false)
  const [renameInput, setRenameInput] = useState("")
  const [renaming, setRenaming] = useState(false)

  const messagesEndRef = useRef<HTMLDivElement>(null)
  const typingTimeoutRef = useRef<ReturnType<typeof setTimeout>>()

  // ── WebSocket ──────────────────────────────────────────────
  const handleWsMessage = useCallback((msg: ChatMessageDto) => {
    // Intercept SYSTEM action messages
    if (msg.messageType === 'SYSTEM' && msg.content.includes('"action"')) {
      let action: string | undefined
      try {
        const data = JSON.parse(msg.content)
        action = data.action
        if (data.action === 'RECALL') {
          setRecalledMessages(prev => new Set(prev).add(data.messageId))
        } else if (data.action === 'REACT') {
          setReactions(prev => ({ ...prev, [data.messageId]: data.emoji }))
        } else if (data.action === 'REACT_REMOVE') {
          setReactions(prev => {
            const next = { ...prev }
            delete next[data.messageId]
            return next
          })
        } else if (data.action === 'PIN') {
          setPinnedMessage({ messageId: data.messageId, content: data.contentStr })
        } else if (data.action === 'UNPIN') {
          setPinnedMessage(prev => prev?.messageId === data.messageId ? null : prev)
        }
      } catch {}
      if (!action || !['stadium_referral', 'match_referral', 'booking_referral'].includes(action)) return
    }

    if (selectedConv && msg.conversationId === selectedConv.conversationId) {
      setMessages(prev => {
        if (prev.some(m => m.messageId === msg.messageId)) return prev
        return [...prev, msg]
      })
      if (msg.senderId !== currentUserId && selectedConv.conversationId) {
        const cid = selectedConv.conversationId;
        setTimeout(() => {
          markConversationAsRead(cid).then(() => refreshUnread()).catch(() => { })
        }, 1000)
      }
    }
    // Update conversation list
    setConversations(prev => {
      const exists = prev.some(c => c.conversationId === msg.conversationId)
      if (!exists) {
        // Unknown conversation (e.g. first time chatting), fetch from API
        loadConversations()
        return prev
      }
      
      const updated = prev.map(c => {
        if (c.conversationId === msg.conversationId) {
          return {
            ...c, lastMessagePreview: msg.content, lastMessageAt: msg.timestamp,
            unreadCount: (selectedConv?.conversationId === msg.conversationId && msg.senderId !== currentUserId) ? 0 : c.unreadCount + (msg.senderId !== currentUserId ? 1 : 0)
          }
        }
        return c
      })
      return updated.sort((a, b) => new Date(b.lastMessageAt || 0).getTime() - new Date(a.lastMessageAt || 0).getTime())
    })
    refreshUnread()
  }, [selectedConv, currentUserId])

  const handleTyping = useCallback((event: TypingEvent) => {
    setTypingUsers(prev => {
      const next = new Map(prev)
      if (event.isTyping) next.set(event.userId, event.userName)
      else next.delete(event.userId)
      return next
    })
  }, [])

  const handleBlockStatus = useCallback((event: BlockEvent) => {
    setConversations(prev => prev.map(c => {
      if (c.otherUserId === event.userId) {
        return { ...c, blocked: event.blocked, blockedByThem: event.blockedByThem }
      }
      return c
    }))
    setSelectedConv(prev => prev?.otherUserId === event.userId
      ? { ...prev, blocked: event.blocked, blockedByThem: event.blockedByThem }
      : prev)
  }, [])

  const handleGroupRenamed = useCallback((event: ConversationDto) => {
    if (!event?.conversationId || !event.otherUserName) return;
    setConversations(prev => prev.map(c => 
      c.conversationId === event.conversationId ? { ...c, otherUserName: event.otherUserName } : c
    ))
    setSelectedConv(prev => (prev?.conversationId === event.conversationId) ? { ...prev, otherUserName: event.otherUserName } : prev)
  }, [])

  const { sendTypingIndicator } = useChatWebSocket({
    userId: currentUserId ?? null,
    onMessage: handleWsMessage,
    onTyping: handleTyping,
    onBlockStatus: handleBlockStatus,
    onGroupRenamed: handleGroupRenamed
  })

  useEffect(() => {
    const handleMessageRead = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (selectedConv && detail.conversationId === selectedConv.conversationId) {
        setMessages(prev => prev.map(m => ({ ...m, isRead: true })))
      }
    }
    window.addEventListener('message_read', handleMessageRead)
    return () => window.removeEventListener('message_read', handleMessageRead)
  }, [selectedConv])

  // ── Data Loading ───────────────────────────────────────────
  const refreshUnread = useCallback(async () => {
    try {
      const r = await getUnreadCount();
      setTotalUnread(r.unreadCount);
      queryClient.setQueryData(['chat', 'unread-count'], r);
    } catch { }
  }, [queryClient])

  useEffect(() => {
    if (!currentUserId) return
    loadConversations()
    refreshUnread()
  }, [currentUserId])

  async function loadConversations() {
    try {
      const data = await getConversations(); setConversations(data)
      const requestedId = typeof window === 'undefined' ? 0
        : Number(new URLSearchParams(window.location.search).get('conversationId'))
      const requested = data.find(c => c.conversationId === requestedId)
      if (requested) selectConversation(requested)
    } catch { }
  }

  async function loadMessages(convId: number) {
    setLoadingMessages(true)
    try {
      const data = await getMessages(convId)
      const rawMessages = [...data.content].reverse()

      const recalls = new Set<number>()
      const reacts: Record<number, string> = {}
      let pinnedMsg: {messageId: number, content: string} | null = null
      const validMessages: ChatMessageDto[] = []

      for (const m of rawMessages) {
        if (m.messageType === 'SYSTEM' && m.content.includes('"action"')) {
          try {
            const parsed = JSON.parse(m.content)
            if (parsed.action === 'RECALL') recalls.add(parsed.messageId)
            if (parsed.action === 'REACT') reacts[parsed.messageId] = parsed.emoji
            if (parsed.action === 'REACT_REMOVE') delete reacts[parsed.messageId]
            if (parsed.action === 'PIN') pinnedMsg = { messageId: parsed.messageId, content: parsed.contentStr }
            if (parsed.action === 'UNPIN' && pinnedMsg?.messageId === parsed.messageId) pinnedMsg = null
            if (['stadium_referral', 'match_referral', 'booking_referral'].includes(parsed.action)) validMessages.push(m)
          } catch {}
        } else {
          validMessages.push(m)
        }
      }

      setRecalledMessages(recalls)
      setReactions(reacts)
      setPinnedMessage(pinnedMsg)
      setMessages(validMessages)
      
      await markConversationAsRead(convId)
      refreshUnread()
      setConversations(prev => prev.map(c => c.conversationId === convId ? { ...c, unreadCount: 0 } : c))
    } catch { } finally { setLoadingMessages(false) }
  }

  function selectConversation(conv: ConversationDto) {
    if (conv.conversationId && unreadOverrides[conv.conversationId]) {
      toggleUnreadOverride(conv.conversationId, false)
    }
    setSelectedConv(conv)
    setShowMobileChat(true)

    // Clear search and add to list if not present
    if (searchQuery) {
      setSearchQuery('')
      setConversations(prev => {
        const exists = prev.find(c =>
          (c.conversationId && c.conversationId === conv.conversationId) ||
          (c.otherUserId && c.otherUserId === conv.otherUserId)
        )
        if (!exists) return [conv, ...prev]
        return prev
      })
    }

    if (conv.conversationId) loadMessages(conv.conversationId)
    else setMessages([])
  }

  // ── Search ─────────────────────────────────────────────────
  useEffect(() => {
    const query = searchQuery.trim()
    if (query.length < 2) {
      setSearchResults([])
      setIsSearching(false)
      return
    }

    let cancelled = false
    const timer = setTimeout(async () => {
      setIsSearching(true)
      try {
        const results = await searchUsers(query)
        if (!cancelled) setSearchResults(results)
      } catch {
        if (!cancelled) setSearchResults([])
      } finally {
        if (!cancelled) setIsSearching(false)
      }
    }, 300)

    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [searchQuery])

  // ── Send Message ───────────────────────────────────────────
  async function handleSendMessage() {
    if (!messageInput.trim() || !selectedConv || !currentUserId) return
    setSendingMessage(true)
    try {
      let finalContent = messageInput.trim()
      if (replyingTo) {
        finalContent = `[REPLY:${replyingTo.senderName || currentUserName}]${replyingTo.content.replace(/^\[REPLY:.*?\][\s\S]*?\[\/REPLY\]/, '')}[/REPLY]${finalContent}`
      }

      const payload: { content: string; conversationId?: number; recipientId?: number } = { content: finalContent }
      if (selectedConv.isGroup) {
        if (!selectedConv.conversationId) return
        payload.conversationId = selectedConv.conversationId
      } else if (selectedConv.otherUserId) {
        payload.recipientId = selectedConv.otherUserId
        if (selectedConv.conversationId) payload.conversationId = selectedConv.conversationId
      } else {
        return
      }

      const msg = await sendMessage(payload)
      setMessages(prev => {
        if (prev.some(m => m.messageId === msg.messageId)) return prev
        return [...prev, msg]
      })
      setMessageInput("")
      
      // Force clear unread state when sending a message
      if (selectedConv.conversationId) {
        setConversations(prev => prev.map(c => 
          c.conversationId === selectedConv.conversationId ? { ...c, unreadCount: 0 } : c
        ))
        if (unreadOverrides[selectedConv.conversationId]) {
          toggleUnreadOverride(selectedConv.conversationId, false)
        }
      }

      if (!selectedConv.conversationId) {
        setSelectedConv(prev => prev ? { ...prev, conversationId: msg.conversationId } : prev)
        loadConversations()
      }
      if (selectedConv.otherUserId) {
        sendTypingIndicator(selectedConv.otherUserId, false)
      }
    } catch (err) { 
      const error = err as { message?: string; status?: number };
      const errorMsg = error?.message || "Lỗi không xác định";
      if (errorMsg.includes("BLOCKED")) {
        toast.error("Không thể gửi tin nhắn: Bạn hoặc người dùng này đang bị chặn.");
        // Revert UI to blocked state to allow them to unblock properly
        setConversations(prev => prev.map(c => 
          c.otherUserId === selectedConv.otherUserId ? { ...c, blocked: true } : c
        ));
        setSelectedConv(prev => prev ? { ...prev, blocked: true } : prev);
      } else {
        toast.error("Lỗi khi gửi tin nhắn: " + errorMsg);
      }
    } finally { 
      setSendingMessage(false) 
    }
  }

  async function handleSendSystemAction(action: 'RECALL' | 'REACT' | 'REACT_REMOVE' | 'PIN' | 'UNPIN', msgId: number, extraData?: Record<string, unknown>) {
    if (!selectedConv || !currentUserId) return
    try {
      const payload: { content: string; messageType: string; conversationId?: number; recipientId?: number } = { 
        content: JSON.stringify({ action, messageId: msgId, ...extraData }),
        messageType: 'SYSTEM'
      }
      if (selectedConv.isGroup) {
        if (!selectedConv.conversationId) return
        payload.conversationId = selectedConv.conversationId
      } else if (selectedConv.otherUserId) {
        payload.recipientId = selectedConv.otherUserId
        if (selectedConv.conversationId) payload.conversationId = selectedConv.conversationId
      } else {
        return
      }
      await sendMessage(payload)
    } catch {}
  }

  // ── Typing Indicator ──────────────────────────────────────
  function handleInputChange(value: string) {
    setMessageInput(value)
    if (selectedConv && selectedConv.otherUserId) {
      sendTypingIndicator(selectedConv.otherUserId, true)
      if (typingTimeoutRef.current) clearTimeout(typingTimeoutRef.current)
      typingTimeoutRef.current = setTimeout(() => {
        if (selectedConv && selectedConv.otherUserId) sendTypingIndicator(selectedConv.otherUserId, false)
      }, 2000)
    }
  }

  // ── Auto-scroll ────────────────────────────────────────────
  useEffect(() => {
    if (!loadingMessages) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages, loadingMessages, selectedConv])

  if (!session) {
    return (
      <div className="h-screen flex flex-col bg-background">
        <Header />
        <div className="flex-1 flex items-center justify-center">
          <div className="text-center space-y-4">
            <MessageSquare className="h-16 w-16 mx-auto text-muted-foreground" />
            <h2 className="text-xl font-semibold">Đăng nhập để sử dụng Chat</h2>
            <p className="text-muted-foreground">Bạn cần đăng nhập để nhắn tin và sử dụng trợ lý AI.</p>
          </div>
        </div>
      </div>
    )
  }

  const isTypingForSelected = selectedConv && selectedConv.otherUserId !== null && typingUsers.has(selectedConv.otherUserId)
  const displayedList = searchQuery.length >= 2 ? searchResults : conversations

  return (
    <div className="h-[100dvh] flex flex-col bg-background overflow-hidden">
      <div className="shrink-0">
        <Header />
      </div>
      <div className="flex-1 min-h-0 container mx-auto px-2 sm:px-4 py-2 sm:py-4 overflow-hidden">
        <div className="h-full flex gap-3 min-h-0">

          {/* ═══ LEFT SIDEBAR ═══ */}
          <div className={`${showMobileChat ? 'hidden md:flex' : 'flex'} w-full md:w-[340px] lg:w-[380px] flex-col bg-card border rounded-xl overflow-hidden flex-shrink-0`}>
            <div className="flex items-center justify-between px-4 py-4">
              <h2 className="text-lg font-bold flex items-center gap-2">
                Đoạn chat
              </h2>
            </div>

            {/* Search */}
            <div className="px-3 pb-3">
              <div className="relative">
                <Search className="absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input placeholder="Tìm người dùng..." className="pl-9 h-10 bg-muted/50 border-transparent focus-visible:ring-1 rounded-full" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
              </div>
            </div>
            <Separator />
            {/* Conversation List */}
            <ScrollArea className="flex-1">
              {isSearching && <div className="flex justify-center py-8"><Loader2 className="h-5 w-5 animate-spin text-muted-foreground" /></div>}
              {!isSearching && displayedList.length === 0 && (
                <div className="text-center py-12 px-4">
                  <MessageSquare className="h-10 w-10 mx-auto text-muted-foreground/50 mb-3" />
                  <p className="text-sm text-muted-foreground">{searchQuery ? 'Không tìm thấy người dùng' : 'Chưa có cuộc trò chuyện nào'}</p>
                </div>
              )}
              {displayedList.map((conv, i) => {
                const isOverride = conv.conversationId ? unreadOverrides[conv.conversationId] : false;
                const displayUnread = isOverride ? Math.max(1, conv.unreadCount) : conv.unreadCount;

                return (
                <button key={(conv.otherUserId || conv.conversationId) + '-' + i} onClick={() => selectConversation(conv)}
                  className={`w-full px-3 py-3 flex gap-2.5 items-center hover:bg-muted/60 transition-all duration-150 ${selectedConv?.conversationId === conv.conversationId ? 'bg-primary/5 border-l-2 border-l-primary' : 'border-l-2 border-l-transparent'}`}>
                  <div className="relative shrink-0">
                    <Avatar className="h-11 w-11">
                      <AvatarImage src={conv.otherUserAvatar || undefined} />
                      <AvatarFallback className={conv.isGroup ? "bg-emerald-100 text-emerald-600" : "bg-primary/10 text-primary font-semibold text-sm"}>
                        {conv.isGroup ? <Users className="h-5 w-5" /> : (conv.otherUserName?.[0]?.toUpperCase() || '?')}
                      </AvatarFallback>
                    </Avatar>
                    {conv.otherUserOnline && <div className="absolute bottom-0 right-0 w-3 h-3 bg-green-500 rounded-full border-2 border-card" />}
                  </div>
                  <div className="flex-1 min-w-0 pr-1 text-left flex flex-col justify-center">
                    <div className="flex items-center justify-between gap-2 mb-1 min-w-0">
                      <h4 className="text-sm font-semibold truncate min-w-0 flex-1">{conv.otherUserName}</h4>
                      {conv.lastMessageAt && (
                        <span className="text-[11px] text-muted-foreground shrink-0 whitespace-nowrap">
                          {formatRelativeTime(conv.lastMessageAt)}
                        </span>
                      )}
                    </div>
                    <div className="flex items-center justify-between min-w-0 gap-2">
                      <p className={`text-xs truncate pr-1 flex-1 min-w-0 ${displayUnread > 0 ? 'text-foreground font-bold' : 'text-muted-foreground'}`}>{formatMessagePreview(conv.lastMessagePreview) || 'Bắt đầu trò chuyện...'}</p>
                      {displayUnread > 0 && <span className="bg-primary text-primary-foreground text-[10px] font-bold px-1.5 py-0.5 rounded-full shrink-0">{displayUnread}</span>}
                    </div>
                  </div>
                </button>
              )})}
            </ScrollArea>
          </div>

          {/* ═══ RIGHT: CHAT WINDOW ═══ */}
          <div className={`${showMobileChat ? 'flex' : 'hidden md:flex'} flex-1 min-h-0 flex-col bg-card border rounded-xl overflow-hidden`}>
            {selectedConv ? (
              <>
                {/* Chat Header */}
                <div className="shrink-0 px-4 py-3 border-b flex items-center justify-between bg-card/80 backdrop-blur-sm">
                  <div className="flex items-center gap-3 min-w-0 flex-1">
                    <Button variant="ghost" size="sm" className="md:hidden h-8 w-8 p-0" onClick={() => setShowMobileChat(false)}>
                      <ArrowLeft className="h-5 w-5" />
                    </Button>
                    <div className="relative shrink-0">
                      <Avatar className="h-10 w-10">
                        <AvatarImage src={selectedConv.otherUserAvatar || undefined} />
                        <AvatarFallback className={selectedConv.isGroup ? "bg-emerald-100 text-emerald-600" : "bg-primary/10 text-primary font-semibold"}>
                          {selectedConv.isGroup ? <Users className="h-5 w-5" /> : selectedConv.otherUserName?.[0]?.toUpperCase()}
                        </AvatarFallback>
                      </Avatar>
                      {selectedConv.otherUserOnline && <div className="absolute bottom-0 right-0 w-3 h-3 bg-green-500 rounded-full border-2 border-card" />}
                    </div>
                    <div className="min-w-0 flex-1">
                      <h3 className="text-sm font-semibold truncate">{selectedConv.otherUserName}</h3>
                      <p className="text-xs text-muted-foreground truncate">
                        {isTypingForSelected ? <span className="text-primary animate-pulse">Đang nhập...</span> : selectedConv.isGroup ? 'Nhóm trò chuyện' : selectedConv.otherUserOnline ? 'Đang hoạt động' : ''}
                      </p>
                    </div>
                  </div>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="sm" className="h-8 w-8 p-0">
                        <MoreVertical className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-56">
                      <DropdownMenuItem className="cursor-pointer" onClick={(e) => {
                        e.stopPropagation()
                        if (selectedConv?.conversationId) {
                          const isUnread = unreadOverrides[selectedConv.conversationId]
                          if (isUnread) {
                            toggleUnreadOverride(selectedConv.conversationId, false)
                            setConversations(prev => prev.map(c =>
                              c.conversationId === selectedConv.conversationId ? { ...c, unreadCount: 0 } : c
                            ))
                          } else {
                            toggleUnreadOverride(selectedConv.conversationId, true)
                            setConversations(prev => prev.map(c =>
                              c.conversationId === selectedConv.conversationId ? { ...c, unreadCount: 1 } : c
                            ))
                          }
                        }
                      }}>
                        {selectedConv?.conversationId && unreadOverrides[selectedConv.conversationId] ? (
                          <CheckCheck className="h-4 w-4 mr-2" />
                        ) : (
                          <EyeOff className="h-4 w-4 mr-2" />
                        )}
                        {selectedConv?.conversationId && unreadOverrides[selectedConv.conversationId] ? 'Đánh dấu là đã đọc' : 'Đánh dấu là chưa đọc'}
                      </DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem className="cursor-pointer" onClick={() => {
                        if (selectedConv?.conversationId) {
                          setRenameInput(selectedConv.otherUserName || '')
                          setRenameModalOpen(true)
                        }
                      }}>
                        <Edit3 className="h-4 w-4 mr-2" /> {selectedConv?.isGroup ? 'Đổi tên nhóm' : 'Đổi biệt danh'}
                      </DropdownMenuItem>
                      {!selectedConv?.isGroup && (
                        selectedConv?.blocked ? (
                          <DropdownMenuItem className="cursor-pointer text-primary focus:text-primary" onClick={() => {
                            if (selectedConv?.otherUserId) {
                              const targetUserId = selectedConv.otherUserId;
                              confirmState.confirm({
                                title: "Bỏ chặn người dùng",
                                description: `Bạn có chắc chắn muốn bỏ chặn ${selectedConv.otherUserName || 'người dùng này'}?`,
                                confirmText: "Bỏ chặn",
                                variant: "default",
                                onConfirm: async () => {
                                  setConversations(prev => prev.map(c =>
                                    c.otherUserId === targetUserId ? { ...c, blocked: false } : c
                                  ))
                                  setSelectedConv(prev => prev ? { ...prev, blocked: false } : prev)

                                  try {
                                    const status = await unblockUser(targetUserId)
                                    setConversations(prev => prev.map(c =>
                                      c.otherUserId === targetUserId
                                        ? { ...c, blocked: status.blocked, blockedByThem: status.blockedByThem }
                                        : c
                                    ))
                                    setSelectedConv(prev => prev?.otherUserId === targetUserId
                                      ? { ...prev, blocked: status.blocked, blockedByThem: status.blockedByThem }
                                      : prev)
                                    toast.success("Đã bỏ chặn người dùng")
                                  } catch (err) {
                                    setConversations(prev => prev.map(c =>
                                      c.otherUserId === targetUserId ? { ...c, blocked: true } : c
                                    ))
                                    setSelectedConv(prev => prev ? { ...prev, blocked: true } : prev)
                                    toast.error("Không thể bỏ chặn người dùng này. Vui lòng thử lại sau.")
                                  }
                                }
                              })
                            }
                          }}>
                            <Ban className="h-4 w-4 mr-2" /> Bỏ chặn người dùng
                          </DropdownMenuItem>
                        ) : (
                          <DropdownMenuItem className="cursor-pointer text-destructive focus:text-destructive" onClick={() => {
                            if (selectedConv?.otherUserId) {
                              const targetUserId = selectedConv.otherUserId;
                              confirmState.confirm({
                                title: "Chặn người dùng",
                                description: `Bạn có chắc chắn muốn chặn ${selectedConv.otherUserName || 'người dùng này'}? Người này sẽ không thể gửi tin nhắn cho bạn.`,
                                confirmText: "Chặn",
                                variant: "destructive",
                                onConfirm: async () => {
                                  setConversations(prev => prev.map(c =>
                                    c.otherUserId === targetUserId ? { ...c, blocked: true } : c
                                  ))
                                  setSelectedConv(prev => prev ? { ...prev, blocked: true } : prev)

                                  try {
                                    const status = await blockUser(targetUserId)
                                    setConversations(prev => prev.map(c =>
                                      c.otherUserId === targetUserId
                                        ? { ...c, blocked: status.blocked, blockedByThem: status.blockedByThem }
                                        : c
                                    ))
                                    setSelectedConv(prev => prev?.otherUserId === targetUserId
                                      ? { ...prev, blocked: status.blocked, blockedByThem: status.blockedByThem }
                                      : prev)
                                    toast.success("Đã chặn người dùng")
                                  } catch (err) {
                                    setConversations(prev => prev.map(c =>
                                      c.otherUserId === targetUserId ? { ...c, blocked: false } : c
                                    ))
                                    setSelectedConv(prev => prev ? { ...prev, blocked: false } : prev)
                                    toast.error("Không thể chặn người dùng này. Vui lòng thử lại sau.")
                                  }
                                }
                              })
                            }
                          }}>
                            <Ban className="h-4 w-4 mr-2" /> Chặn người dùng
                          </DropdownMenuItem>
                        )
                      )}
                      {selectedConv?.isGroup && (
                        <DropdownMenuItem className="cursor-pointer text-destructive focus:text-destructive" onClick={() => {
                          if (selectedConv?.conversationId) {
                            const convId = selectedConv.conversationId;
                            confirmState.confirm({
                              title: "Rời khỏi nhóm",
                              description: `Bạn có chắc chắn muốn rời khỏi nhóm ${selectedConv.otherUserName}?`,
                              confirmText: "Rời nhóm",
                              variant: "destructive",
                              onConfirm: async () => {
                                try {
                                  await leaveGroupChat(convId)
                                  setConversations(prev => prev.filter(c => c.conversationId !== convId))
                                  setSelectedConv(null)
                                  toast.success("Đã rời khỏi nhóm")
                                } catch {
                                  toast.error("Không thể rời khỏi nhóm. Vui lòng thử lại sau.")
                                }
                              }
                            })
                          }
                        }}>
                          <LogOut className="h-4 w-4 mr-2" /> Rời khỏi nhóm
                        </DropdownMenuItem>
                      )}
                      <DropdownMenuItem className="cursor-pointer text-destructive focus:text-destructive" onClick={async () => {
                        if (selectedConv?.conversationId) {
                          try {
                            await deleteConversation(selectedConv.conversationId)
                            setConversations(prev => prev.filter(c => c.conversationId !== selectedConv.conversationId))
                            setSelectedConv(null)
                          } catch { }
                        }
                      }}>
                        <Trash2 className="h-4 w-4 mr-2" /> Xóa đoạn chat
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>

                {pinnedMessage && (
                  <div className="bg-muted/30 px-4 py-2 border-b flex items-center justify-between shadow-sm cursor-pointer hover:bg-muted/50 transition-colors" onClick={() => {
                    const el = document.getElementById(`msg-${pinnedMessage.messageId}`)
                    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
                    el?.classList.add('animate-pulse')
                    setTimeout(() => el?.classList.remove('animate-pulse'), 2000)
                  }}>
                    <div className="flex flex-col min-w-0 pr-4">
                      <span className="text-[10px] font-bold text-primary uppercase flex items-center gap-1 mb-0.5"><Pin className="h-3 w-3 fill-primary text-primary" /> Tin nhắn đã ghim</span>
                      <span className="text-xs truncate text-muted-foreground">{pinnedMessage.content}</span>
                    </div>
                    <Button variant="ghost" size="sm" onClick={(e) => { e.stopPropagation(); handleSendSystemAction('UNPIN', pinnedMessage.messageId); setPinnedMessage(null) }} className="h-6 w-6 p-0 rounded-full shrink-0"><X className="h-3 w-3" /></Button>
                  </div>
                )}

                {/* Messages Area */}
                <ScrollArea className="flex-1 min-h-0 p-4">
                  {loadingMessages ? (
                    <div className="flex justify-center py-12"><Loader2 className="h-6 w-6 animate-spin text-muted-foreground" /></div>
                  ) : messages.length === 0 ? (
                    <div className="text-center py-16">
                      <MessageSquare className="h-12 w-12 mx-auto text-muted-foreground/30 mb-3" />
                      <p className="text-sm text-muted-foreground">Bắt đầu cuộc trò chuyện!</p>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {messages.map((msg, index) => {
                          const isMe = msg.senderId === currentUserId
                          const isLast = index === messages.length - 1
                          const showStatus = isLast

                        const quickEmojis = ["❤️", "😆", "😮", "😢", "😡", "👍"]

                        let replyName = null;
                        let replyContent = null;
                        let mainContent = msg.content;
                        const replyRegex = /^\[REPLY:(.*?)\]([\s\S]*?)\[\/REPLY\]([\s\S]*)$/;
                        const match = msg.content.match(replyRegex);
                        if (match) {
                          replyName = match[1];
                          replyContent = match[2];
                          mainContent = match[3];
                        }
                        
                        if (recalledMessages.has(msg.messageId)) {
                          return (
                            <div key={msg.messageId} className={`flex ${isMe ? 'justify-end' : 'justify-start'} animate-fade-in-up my-1`}>
                              <div className={`px-3.5 py-2 border border-muted text-muted-foreground italic rounded-2xl ${isMe ? 'rounded-br-md' : 'rounded-bl-md'} text-sm`}>
                                Tin nhắn đã được thu hồi
                              </div>
                            </div>
                          )
                        }
                        
                        if (msg.messageType === 'SYSTEM') {
                          let context: any = null
                          try { context = JSON.parse(mainContent) } catch {}
                          if (context?.action === 'stadium_referral' || context?.action === 'booking_referral' || context?.action === 'match_referral') {
                            if (context.action === 'stadium_referral') {
                              const cached = context.stadiumId ? venueCache[context.stadiumId] : null
                              const complexName = context.complexName || cached?.complexName
                              const facilityName = context.facilityName || cached?.facilityName
                              const stadiumName = context.stadiumName || cached?.stadiumName || context.title || 'Sân thể thao'
                              const complexId = context.complexId || cached?.complexId
                              const facilityId = context.facilityId || cached?.facilityId
                              const nodeType = context.nodeType || cached?.nodeType

                              const fullContext = {
                                ...context,
                                complexId,
                                complexName,
                                facilityId,
                                facilityName,
                                stadiumName,
                                nodeType
                              }

                              return (
                                <div key={msg.messageId} className="flex justify-center my-3">
                                  <div 
                                    onClick={() => handleContextCardClick(fullContext)}
                                    className="w-full max-w-sm rounded-xl border border-emerald-200 bg-emerald-50/80 p-3 shadow-sm hover:shadow-md hover:border-emerald-500 cursor-pointer transition-all group"
                                  >
                                    <div className="flex items-center justify-between">
                                      <p className="text-[10px] font-bold uppercase tracking-wider text-emerald-700">
                                        Ngữ cảnh trao đổi • Sân
                                      </p>
                                      <span className="text-[10px] text-emerald-600 font-medium group-hover:underline flex items-center gap-0.5">
                                        Xem chi tiết &rarr;
                                      </span>
                                    </div>

                                    {complexName ? (
                                      <>
                                        <p className="mt-1 text-sm font-bold text-slate-900 leading-snug">
                                          🏢 {complexName}
                                        </p>
                                        <p className="mt-0.5 text-xs text-slate-600 font-medium">
                                          📍 {facilityName ? `${facilityName} › ` : ''}{stadiumName !== complexName ? stadiumName : 'Tổ hợp sân'}
                                        </p>
                                      </>
                                    ) : facilityName ? (
                                      <>
                                        <p className="mt-1 text-sm font-bold text-slate-900 leading-snug">
                                          🏟️ {facilityName}
                                        </p>
                                        <p className="mt-0.5 text-xs text-slate-600 font-medium">
                                          📍 {stadiumName}
                                        </p>
                                      </>
                                    ) : (
                                      <p className="mt-1 text-sm font-semibold text-slate-900">
                                        🏟️ {stadiumName}
                                      </p>
                                    )}
                                  </div>
                                </div>
                              )
                            }

                            if (context.action === 'booking_referral') {
                              const cached = context.stadiumId ? venueCache[context.stadiumId] : null
                              const complexName = context.complexName || cached?.complexName
                              const facilityName = context.facilityName || cached?.facilityName
                              const stadiumName = context.stadiumName || cached?.stadiumName || 'Sân thể thao'
                              const timeDetail = context.playDate
                                ? `${context.playDate}${context.time ? ` · ${context.time}` : ''}`
                                : null

                              const fullContext = {
                                ...context,
                                complexId: context.complexId || cached?.complexId,
                                complexName,
                                facilityId: context.facilityId || cached?.facilityId,
                                facilityName,
                                stadiumName
                              }

                              return (
                                <div key={msg.messageId} className="flex justify-center my-3">
                                  <div 
                                    onClick={() => handleContextCardClick(fullContext)}
                                    className="w-full max-w-sm rounded-xl border border-emerald-200 bg-emerald-50/80 p-3 shadow-sm hover:shadow-md hover:border-emerald-500 cursor-pointer transition-all group"
                                  >
                                    <div className="flex items-center justify-between">
                                      <p className="text-[10px] font-bold uppercase tracking-wider text-emerald-700">
                                        Ngữ cảnh trao đổi • Booking #{context.bookingId}
                                      </p>
                                      <span className="text-[10px] text-emerald-600 font-medium group-hover:underline flex items-center gap-0.5">
                                        Xem chi tiết &rarr;
                                      </span>
                                    </div>

                                    <p className="mt-1 text-sm font-bold text-slate-900 leading-snug">
                                      🏢 {complexName || facilityName || 'Tổ hợp sân'}
                                    </p>
                                    <p className="mt-0.5 text-xs text-slate-600 font-medium">
                                      📍 {facilityName && complexName ? `${facilityName} › ` : ''}{stadiumName}
                                    </p>
                                    {timeDetail && (
                                      <div className="mt-1.5 inline-flex items-center gap-1 rounded-md bg-emerald-100/70 px-2 py-0.5 text-[11px] font-medium text-emerald-800">
                                        📅 {timeDetail}
                                      </div>
                                    )}
                                  </div>
                                </div>
                              )
                            }

                            const title = context.title || 'Kèo thi đấu'
                            const detail = context.playDate
                              ? `${context.playDate}${context.time ? ` · ${context.time}` : ''}`
                              : context.sportName || 'Thông tin từ hệ thống'

                            return (
                              <div key={msg.messageId} className="flex justify-center my-3">
                                <div 
                                  onClick={() => handleContextCardClick(context)}
                                  className="w-full max-w-sm rounded-xl border border-emerald-200 bg-emerald-50/80 p-3 shadow-sm hover:shadow-md hover:border-emerald-500 cursor-pointer transition-all group"
                                >
                                  <div className="flex items-center justify-between">
                                    <p className="text-[10px] font-bold uppercase tracking-wider text-emerald-700">Ngữ cảnh trao đổi • Kèo giao lưu</p>
                                    <span className="text-[10px] text-emerald-600 font-medium group-hover:underline">Xem chi tiết &rarr;</span>
                                  </div>
                                  <p className="mt-1 text-sm font-semibold text-slate-900">{title}</p>
                                  <p className="text-xs text-slate-600">{detail}</p>
                                </div>
                              </div>
                            )
                          }
                          return (
                            <div key={msg.messageId} className="flex justify-center my-2 animate-fade-in-up">
                              <div className="bg-muted/50 px-4 py-1.5 rounded-full text-xs text-muted-foreground font-medium border shadow-sm">
                                {mainContent}
                              </div>
                            </div>
                          )
                        }

                        return (
                          <div key={msg.messageId} className={`flex group items-center ${isMe ? 'justify-end' : 'justify-start'} animate-fade-in-up ${reactions[msg.messageId] ? 'mb-4 z-10' : 'mb-2'}`}>
                            {isMe && (
                              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity mr-2">
                                <Button variant="ghost" size="icon" className="h-8 w-8 rounded-full hover:bg-muted" onClick={() => setReplyingTo(msg)}><Reply className="h-4 w-4 text-muted-foreground" /></Button>
                                
                                <Popover>
                                  <PopoverTrigger asChild>
                                    <Button variant="ghost" size="icon" className="h-8 w-8 rounded-full hover:bg-muted"><Smile className="h-4 w-4 text-muted-foreground" /></Button>
                                  </PopoverTrigger>
                                  <PopoverContent side="top" align="end" className="w-auto p-1.5 rounded-full flex items-center gap-1 bg-card shadow-md border">
                                    {quickEmojis.map(emoji => (
                                      <button key={emoji} className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-muted text-lg transition-transform hover:scale-125" onClick={() => {
                                        setReactions(prev => ({ ...prev, [msg.messageId]: emoji }))
                                        handleSendSystemAction('REACT', msg.messageId, { emoji })
                                      }}>
                                        {emoji}
                                      </button>
                                    ))}
                                  </PopoverContent>
                                </Popover>

                                <DropdownMenu>
                                  <DropdownMenuTrigger asChild>
                                    <Button variant="ghost" size="icon" className="h-8 w-8 rounded-full hover:bg-muted"><MoreHorizontal className="h-4 w-4 text-muted-foreground" /></Button>
                                  </DropdownMenuTrigger>
                                  <DropdownMenuContent align="end" className="w-40 rounded-xl">
                                    <DropdownMenuItem className="cursor-pointer" onClick={() => {
                                      setRecalledMessages(prev => new Set(prev).add(msg.messageId))
                                      handleSendSystemAction('RECALL', msg.messageId)
                                    }}>Thu hồi</DropdownMenuItem>
                                    <DropdownMenuItem className="cursor-pointer" onClick={() => setForwardingMessage(msg)}>Chuyển tiếp</DropdownMenuItem>
                                    <DropdownMenuItem className="cursor-pointer" onClick={() => {
                                      if (pinnedMessage?.messageId === msg.messageId) {
                                        setPinnedMessage(null)
                                        handleSendSystemAction('UNPIN', msg.messageId)
                                      } else {
                                        setPinnedMessage({ messageId: msg.messageId, content: mainContent })
                                        handleSendSystemAction('PIN', msg.messageId, { contentStr: mainContent })
                                      }
                                    }}>{pinnedMessage?.messageId === msg.messageId ? 'Bỏ ghim' : 'Ghim'}</DropdownMenuItem>
                                  </DropdownMenuContent>
                                </DropdownMenu>
                              </div>
                            )}
                            <div id={`msg-${msg.messageId}`} className={`flex flex-col ${isMe ? 'items-end' : 'items-start'} max-w-[75%] sm:max-w-sm md:max-w-md`}>
                              {replyContent && (
                                <div className={`flex flex-col ${isMe ? 'items-end' : 'items-start'} mb-1 w-full`}>
                                  <div className="text-xs text-muted-foreground flex items-center gap-1 mb-1 font-medium ml-1 mr-1">
                                    <Reply className="h-3 w-3" />
                                    {isMe ? (replyName === currentUserName ? 'Bạn đã trả lời chính mình' : `Bạn đã trả lời ${replyName}`) : `${msg.senderName} đã trả lời ${replyName}`}
                                  </div>
                                  <div className="bg-muted/60 text-muted-foreground px-3 py-1.5 rounded-2xl text-xs max-w-[85%] truncate opacity-80">
                                    {replyContent}
                                  </div>
                                </div>
                              )}
                              <div className={`relative ${isMe ? 'bg-primary text-primary-foreground rounded-2xl rounded-br-md' : 'bg-muted rounded-2xl rounded-bl-md'} px-3.5 py-2 ${reactions[msg.messageId] ? 'mb-3' : ''}`}>
                                {selectedConv.isGroup && !isMe && (
                                  <p className="text-xs font-semibold text-primary mb-1">{msg.senderName}</p>
                                )}
                                <p className="text-sm whitespace-pre-wrap break-words">{mainContent}</p>
                                
                                {/* Reaction Badge */}
                                {reactions[msg.messageId] && (
                                  <button 
                                    className={`absolute -bottom-2.5 ${isMe ? 'right-0' : '-right-2'} bg-background border shadow-sm rounded-full px-1 py-0 text-[11px] animate-in zoom-in hover:bg-muted cursor-pointer transition-transform hover:scale-110 z-20`}
                                    onClick={() => {
                                      setReactions(prev => {
                                        const next = { ...prev }
                                        delete next[msg.messageId]
                                        return next
                                      })
                                      handleSendSystemAction('REACT_REMOVE', msg.messageId)
                                    }}
                                  >
                                    {reactions[msg.messageId]}
                                  </button>
                                )}
                              </div>
                              {showStatus && (
                                <div className={`flex items-center gap-1 mt-1 ${isMe ? 'justify-end' : 'justify-start'}`}>
                                  <span className="text-[10px] text-muted-foreground">
                                    {isMe
                                      ? (msg.isRead ? 'Đã xem' : (isLast ? `Đã gửi ${formatRelativeTime(msg.timestamp || msg.sentAt)}` : ''))
                                      : (isLast ? formatRelativeTime(msg.timestamp || msg.sentAt) : '')}
                                  </span>
                                  {isMe && msg.isRead && (
                                    <div className="flex -space-x-1 ml-1">
                                      {selectedConv.isGroup ? (
                                        msg.readByNames?.slice(0, 3).map((name, idx) => {
                                          const avatar = msg.readByAvatars?.[idx]
                                          return (
                                            <div key={idx} className="w-3.5 h-3.5 rounded-full overflow-hidden border border-background shrink-0 z-10 bg-card" style={{ zIndex: 10 - idx }} title={name}>
                                              {avatar ? (
                                                <img src={avatar} className="w-full h-full object-cover" alt="Seen" />
                                              ) : (
                                                <div className="w-full h-full bg-emerald-100 flex items-center justify-center text-[7px] text-emerald-600 font-bold">{name?.[0]?.toUpperCase()}</div>
                                              )}
                                            </div>
                                          )
                                        })
                                      ) : (
                                        <div className="w-3.5 h-3.5 rounded-full overflow-hidden border border-background shrink-0 bg-card">
                                          {selectedConv.otherUserAvatar ? (
                                            <img src={selectedConv.otherUserAvatar} className="w-full h-full object-cover" alt="Seen" />
                                          ) : (
                                            <div className="w-full h-full bg-emerald-100 flex items-center justify-center text-[7px] text-emerald-600 font-bold">{selectedConv.otherUserName?.[0]?.toUpperCase()}</div>
                                          )}
                                        </div>
                                      )}
                                    </div>
                                  )}
                                </div>
                              )}
                            </div>
                            {!isMe && (
                              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity ml-2">
                                <Popover>
                                  <PopoverTrigger asChild>
                                    <Button variant="ghost" size="icon" className="h-8 w-8 rounded-full hover:bg-muted"><Smile className="h-4 w-4 text-muted-foreground" /></Button>
                                  </PopoverTrigger>
                                  <PopoverContent side="top" align="start" className="w-auto p-1.5 rounded-full flex items-center gap-1 bg-card shadow-md border">
                                    {quickEmojis.map(emoji => (
                                      <button key={emoji} className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-muted text-lg transition-transform hover:scale-125" onClick={() => {
                                        setReactions(prev => ({ ...prev, [msg.messageId]: emoji }))
                                        handleSendSystemAction('REACT', msg.messageId, { emoji })
                                      }}>
                                        {emoji}
                                      </button>
                                    ))}
                                  </PopoverContent>
                                </Popover>

                                <Button variant="ghost" size="icon" className="h-8 w-8 rounded-full hover:bg-muted" onClick={() => setReplyingTo(msg)}><Reply className="h-4 w-4 text-muted-foreground" /></Button>

                                <DropdownMenu>
                                  <DropdownMenuTrigger asChild>
                                    <Button variant="ghost" size="icon" className="h-8 w-8 rounded-full hover:bg-muted"><MoreHorizontal className="h-4 w-4 text-muted-foreground" /></Button>
                                  </DropdownMenuTrigger>
                                  <DropdownMenuContent align="start" className="w-40 rounded-xl">
                                    <DropdownMenuItem className="cursor-pointer" onClick={() => setForwardingMessage(msg)}>Chuyển tiếp</DropdownMenuItem>
                                    <DropdownMenuItem className="cursor-pointer" onClick={() => {
                                      if (pinnedMessage?.messageId === msg.messageId) {
                                        setPinnedMessage(null)
                                        handleSendSystemAction('UNPIN', msg.messageId)
                                      } else {
                                        setPinnedMessage({ messageId: msg.messageId, content: mainContent })
                                        handleSendSystemAction('PIN', msg.messageId, { contentStr: mainContent })
                                      }
                                    }}>{pinnedMessage?.messageId === msg.messageId ? 'Bỏ ghim' : 'Ghim'}</DropdownMenuItem>
                                  </DropdownMenuContent>
                                </DropdownMenu>
                              </div>
                            )}
                          </div>
                        )
                      })}
                      {isTypingForSelected && (
                        <div className="flex justify-start">
                          <div className="bg-muted rounded-2xl rounded-bl-md px-4 py-2.5">
                            <div className="flex gap-1.5">
                              <div className="w-2 h-2 bg-muted-foreground/60 rounded-full animate-bounce" />
                              <div className="w-2 h-2 bg-muted-foreground/60 rounded-full animate-bounce" style={{ animationDelay: '0.1s' }} />
                              <div className="w-2 h-2 bg-muted-foreground/60 rounded-full animate-bounce" style={{ animationDelay: '0.2s' }} />
                            </div>
                          </div>
                        </div>
                      )}
                      <div ref={messagesEndRef} />
                    </div>
                  )}
                </ScrollArea>

                {/* Message Input */}
                <div className="shrink-0 flex flex-col border-t bg-card/80 backdrop-blur-sm">
                  {replyingTo && (
                    <div className="flex items-center justify-between bg-muted/30 px-4 py-2 border-b">
                      <div className="text-xs text-muted-foreground truncate border-l-2 border-primary pl-2">
                        Đang trả lời <span className="font-semibold">{replyingTo.senderName || currentUserName}</span>: {replyingTo.content.replace(/^\[REPLY:.*?\][\s\S]*?\[\/REPLY\]/, '')}
                      </div>
                      <Button variant="ghost" size="sm" onClick={() => setReplyingTo(null)} className="h-6 w-6 p-0 rounded-full hover:bg-muted-foreground/20"><X className="h-3 w-3" /></Button>
                    </div>
                  )}
                  <div className="px-4 py-3">
                    {selectedConv.blocked ? (
                      <div className="text-center py-2 text-sm text-destructive font-medium bg-destructive/10 rounded-lg">Bạn đã chặn người dùng này</div>
                    ) : selectedConv.blockedByThem ? (
                      <div className="text-center py-2 text-sm text-muted-foreground bg-muted rounded-lg">Bạn không thể trả lời cuộc trò chuyện này</div>
                    ) : (
                      <div className="flex gap-2 items-end">
                        <Input placeholder="Nhập tin nhắn..." value={messageInput}
                          onChange={(e) => handleInputChange(e.target.value)}
                          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && messageInput.trim()) { e.preventDefault(); handleSendMessage() } }}
                          className="h-10 rounded-full px-4 bg-muted/50 border-none focus-visible:ring-1" disabled={sendingMessage} />
                        <Button className="h-10 w-10 rounded-full p-0 flex-shrink-0" disabled={!messageInput.trim() || sendingMessage} onClick={handleSendMessage}>
                          {sendingMessage ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                        </Button>
                      </div>
                    )}
                  </div>
                </div>
              </>
            ) : (
              /* Empty state */
              <div className="flex-1 flex items-center justify-center">
                <div className="text-center space-y-4 p-8">
                  <div className="w-20 h-20 mx-auto rounded-full bg-gradient-to-br from-primary/20 to-emerald-500/20 flex items-center justify-center">
                    <MessageSquare className="h-10 w-10 text-primary" />
                  </div>
                  <h2 className="text-lg font-semibold">Chọn một cuộc trò chuyện</h2>
                  <p className="text-sm text-muted-foreground max-w-sm">Chọn một người bạn từ danh sách bên trái hoặc tìm kiếm để bắt đầu trò chuyện mới.</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      <Dialog open={!!forwardingMessage} onOpenChange={(open) => {
        if (!open) { setForwardingMessage(null); setSentForwards(new Set()) }
      }}>
        <DialogContent className="sm:max-w-md bg-card border-none shadow-2xl rounded-2xl">
          <DialogHeader className="border-b pb-3 mb-2">
            <DialogTitle className="text-center text-lg font-bold">Chuyển tiếp</DialogTitle>
          </DialogHeader>
          <div className="px-1">
            <div className="relative mb-4">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <Input placeholder="Tìm kiếm người và nhóm" className="pl-9 h-10 bg-muted/50 rounded-full border-none focus-visible:ring-0" value={forwardSearch} onChange={e => setForwardSearch(e.target.value)} />
            </div>
            <h4 className="text-sm font-semibold mb-3">Mới đây</h4>
            <ScrollArea className="h-[300px] pr-2">
              <div className="space-y-4">
                {conversations.filter(c => c.otherUserName?.toLowerCase().includes(forwardSearch.toLowerCase())).map(conv => (
                  <div key={conv.conversationId || conv.otherUserId} className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <Avatar className="h-11 w-11">
                        <AvatarImage src={conv.otherUserAvatar || ''} />
                        <AvatarFallback>{conv.otherUserName?.[0]?.toUpperCase()}</AvatarFallback>
                      </Avatar>
                      <span className="font-semibold text-[15px]">{conv.otherUserName}</span>
                    </div>
                    <Button 
                      size="sm" 
                      disabled={sentForwards.has(conv.conversationId ?? conv.otherUserId ?? 0)}
                      onClick={async () => {
                        const targetId = conv.conversationId ?? conv.otherUserId
                        if (!currentUserId || !forwardingMessage || !targetId || (!conv.isGroup && !conv.otherUserId)) return
                        try {
                          const payload: { content: string; conversationId?: number; recipientId?: number } = { content: forwardingMessage.content }
                          if (conv.isGroup) {
                            if (!conv.conversationId) return
                            payload.conversationId = conv.conversationId
                          }
                          else if (conv.otherUserId) {
                            payload.recipientId = conv.otherUserId
                            if (conv.conversationId) payload.conversationId = conv.conversationId
                          } else {
                            return
                          }
                          
                          const sentMsg = await sendMessage(payload)
                          setSentForwards(prev => new Set(prev).add(targetId))
                          
                          if (selectedConv?.conversationId === conv.conversationId) setMessages(prev => [...prev, sentMsg])
                          setConversations(prev => prev.map(c => 
                            c.conversationId === conv.conversationId || c.otherUserId === conv.otherUserId ? { ...c, lastMessagePreview: sentMsg.content, lastMessageAt: sentMsg.sentAt ?? sentMsg.timestamp } : c
                          ).sort((a, b) => new Date(b.lastMessageAt || 0).getTime() - new Date(a.lastMessageAt || 0).getTime()))
                        } catch {}
                      }} 
                      className={`rounded-lg px-5 py-4 font-semibold shadow-none transition-colors ${sentForwards.has(conv.conversationId ?? conv.otherUserId ?? 0) ? 'bg-muted text-muted-foreground cursor-not-allowed' : 'bg-primary/10 text-primary hover:bg-primary/20'}`}
                    >
                      {sentForwards.has(conv.conversationId ?? conv.otherUserId ?? 0) ? 'Đã gửi' : 'Gửi'}
                    </Button>
                  </div>
                ))}
                {conversations.filter(c => c.otherUserName?.toLowerCase().includes(forwardSearch.toLowerCase())).length === 0 && (
                  <div className="text-center py-8 text-muted-foreground text-sm">Không tìm thấy kết quả</div>
                )}
              </div>
            </ScrollArea>
          </div>
        </DialogContent>
      </Dialog>
      {/* Modal Đổi tên nhóm / đặt biệt danh */}
      <Dialog open={renameModalOpen} onOpenChange={setRenameModalOpen}>
        <DialogContent className="sm:max-w-[425px]">
          <DialogHeader>
            <DialogTitle>{selectedConv?.isGroup ? 'Đổi tên nhóm' : 'Đặt biệt danh'}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <p className="text-xs text-muted-foreground">
              {selectedConv?.isGroup
                ? 'Nhập tên nhóm mới để áp dụng cho tất cả thành viên.'
                : 'Nhập biệt danh mới cho người này (chỉ bạn mới thấy biệt danh này).'}
            </p>
            <Input
              value={renameInput}
              onChange={(e) => setRenameInput(e.target.value)}
              placeholder="Nhập tên mới..."
              onKeyDown={async (e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  if (!selectedConv?.conversationId || !renameInput.trim()) return
                  const isGroup = selectedConv.isGroup
                  try {
                    setRenaming(true)
                    await renameGroupChat(selectedConv.conversationId, renameInput.trim())
                    setConversations(prev => prev.map(c =>
                      c.conversationId === selectedConv.conversationId ? { ...c, otherUserName: renameInput.trim() } : c
                    ))
                    setSelectedConv(prev => prev ? { ...prev, otherUserName: renameInput.trim() } : prev)
                    setRenameModalOpen(false)
                    toast.success(isGroup ? "Đã đổi tên nhóm thành công" : "Đã cập nhật biệt danh thành công")
                  } catch (err: any) {
                    const isNetworkError = !err.response && err.message?.includes('Network')
                    if (isNetworkError) {
                      toast.error("Lỗi mạng: Không thể kết nối đến máy chủ. Vui lòng kiểm tra xem Backend đã chạy chưa.")
                    } else {
                      toast.error(isGroup ? "Không thể đổi tên nhóm. Bạn có thể không phải là thành viên của nhóm này." : "Không thể đặt biệt danh. Vui lòng thử lại sau.")
                    }
                  } finally {
                    setRenaming(false)
                  }
                }
              }}
            />
          </div>
          <DialogFooter className="flex gap-2 justify-end">
            <Button variant="outline" onClick={() => setRenameModalOpen(false)} disabled={renaming}>
              Hủy
            </Button>
            <Button onClick={async () => {
              if (!selectedConv?.conversationId || !renameInput.trim()) return
              const isGroup = selectedConv.isGroup
              try {
                setRenaming(true)
                await renameGroupChat(selectedConv.conversationId, renameInput.trim())
                setConversations(prev => prev.map(c =>
                  c.conversationId === selectedConv.conversationId ? { ...c, otherUserName: renameInput.trim() } : c
                ))
                setSelectedConv(prev => prev ? { ...prev, otherUserName: renameInput.trim() } : prev)
                setRenameModalOpen(false)
                toast.success(isGroup ? "Đã đổi tên nhóm thành công" : "Đã cập nhật biệt danh thành công")
              } catch (err: any) {
                const isNetworkError = !err.response && err.message?.includes('Network')
                if (isNetworkError) {
                  toast.error("Lỗi mạng: Không thể kết nối đến máy chủ. Vui lòng kiểm tra xem Backend đã chạy chưa.")
                } else {
                  toast.error(isGroup ? "Không thể đổi tên nhóm. Bạn có thể không phải là thành viên của nhóm này." : "Không thể đặt biệt danh. Vui lòng thử lại sau.")
                }
              } finally {
                setRenaming(false)
              }
            }} disabled={renaming || !renameInput.trim()}>
              {renaming && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
              Lưu
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ConfirmDialog cho Chặn, Bỏ chặn, Rời nhóm */}
      <ConfirmDialog
        isOpen={confirmState.isOpen}
        onClose={confirmState.close}
        onConfirm={confirmState.execute}
        title={confirmState.options?.title || ""}
        description={confirmState.options?.description || ""}
        confirmText={confirmState.options?.confirmText}
        cancelText={confirmState.options?.cancelText}
        variant={confirmState.options?.variant}
        isLoading={confirmState.isLoading}
      />
    </div>
  )
}

export default ChatPage
