'use client'

import { useState, useEffect } from 'react'
import dynamic from 'next/dynamic'
import Image from 'next/image'
import { useRouter } from 'next/navigation'
import { useSession } from 'next-auth/react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  IconBallFootball,
  IconClock,
  IconCategory,
  IconCircleCheck,
  IconMapPin,
  IconMap2,
  IconStar,
  IconStarHalf,
  IconCalendarPlus,
  IconPackage,
  IconMessageCircle,
  IconMessageOff,
  IconCar,
  IconUser,
  IconDroplet,
  IconCoffee,
  IconWifi,
  IconInfoCircle,
  IconMaximize,
  IconX,
  IconArrowLeft,
  IconArrowRight,
  IconChevronLeft,
  IconChevronRight
} from '@tabler/icons-react'
import { createBooking } from '@/lib/bookings-api'
import WeeklySchedule from './WeeklySchedule'
import EditReviewForm from './EditReviewForm'
import WriteReviewForm from './WriteReviewForm'
import { useRouteGuard } from '@/components/shared/RouteGuard'
import { chatUrl, createContextualConversation } from '@/lib/contextual-chat'

// Lazy load the Leaflet Map to avoid SSR issues and reduce bundle size
const VenueMap = dynamic(() => import('./VenueMap'), {
  ssr: false,
  loading: () => (
    <div className="h-[180px] bg-gray-100 flex items-center justify-center rounded-[8px] animate-pulse border-[0.5px] border-gray-200">
      <span className="text-[12px] text-gray-400">Đang tải bản đồ...</span>
    </div>
  )
})

export interface VenueDetailProps {
  venue: {
    id: number
    name: string
    complexId?: number
    complexName?: string
    facilityId?: number
    facilityName?: string
    nodeType?: 'COMPLEX' | 'FACILITY' | 'COURT'
    sport: string
    address: string
    rating: number
    reviewCount: number
    pricePerHour: number
    openTime: string        // "06:00"
    closeTime: string       // "22:00"
    status: string
    description: string
    images: string[]        // array of image URLs, length >= 5
    amenities: string[]
    footballFieldType?: 'FIVE_A_SIDE' | 'SEVEN_A_SIDE' | 'ELEVEN_A_SIDE' | 'FUTSAL' | null
    timeSlots: {
      date: string          // "YYYY-MM-DD"
      hour: string          // "07:00"
      available: boolean
    }[]
    services: {
      name: string
      stock: number
      price: number
    }[]
    owner: {
      userId?: number
      name: string
      initials: string
      phone: string
    }
    coordinates: {
      lat: number
      lng: number
    }
    recentReviews?: {
      reviewId: number
      userId?: number
      userName: string
      userAvatar: string | null
      ratingScore: number
      comment: string
      ownerResponse: string | null
      createdAt: string
    }[]
  }
}

const IMAGE_LABELS = [
  "Sân chính — góc nhìn tổng thể",
  "Cỏ nhân tạo thế hệ 3",
  "Phòng thay đồ",
  "Bãi đỗ xe",
  "Căng tin & khu vực nghỉ"
]

export default function VenueDetail({ venue }: VenueDetailProps) {
  const router = useRouter()
  const { data: session } = useSession()
  const queryClient = useQueryClient()
  const [activeIndex, setActiveIndex] = useState(0)
  const [activeTab, setActiveTab] = useState<string>('overview')
  const [lightboxOpen, setLightboxOpen] = useState(false)
  const [lightboxIndex, setLightboxIndex] = useState(0)
  const [mapLoaded, setMapLoaded] = useState(false)
  const [editingReviewId, setEditingReviewId] = useState<number | null>(null)

  const fieldTypeMapping: Record<string, string> = {
    FIVE_A_SIDE: 'Sân 5 người',
    SEVEN_A_SIDE: 'Sân 7 người',
    ELEVEN_A_SIDE: 'Sân 11 người',
    FUTSAL: 'Sân Futsal',
  }

  // UC-CUS-01: booking state — selected slot + date được WeeklySchedule set
  // khi user click một ô AVAILABLE.
  const [selectedDate, setSelectedDate] = useState<Date>(() => {
    const d = new Date()
    d.setHours(0, 0, 0, 0)
    return d
  })
  const [selectedSlot, setSelectedSlot] = useState<number | null>(null)
  const [selectedSlotTime, setSelectedSlotTime] = useState<string>('')
  const [selectedSlotEndTime, setSelectedSlotEndTime] = useState<string>('')
  const [selectedSlotPrice, setSelectedSlotPrice] = useState<number | null>(null)
  const [bookingSubmitting, setBookingSubmitting] = useState(false)
  const [chatStarting, setChatStarting] = useState(false)

  const handleMessageOwner = async () => {
    if (!session) {
      triggerLoginModal(window.location.pathname)
      return
    }
    if (!venue.owner.userId) {
      toast.error('Không tìm thấy tài khoản chủ sân')
      return
    }
    try {
      setChatStarting(true)
      const conversationId = await createContextualConversation(venue.owner.userId, {
        action: 'stadium_referral',
        stadiumId: venue.id,
        stadiumName: venue.name,
        complexId: venue.complexId,
        complexName: venue.complexName,
        facilityId: venue.facilityId,
        facilityName: venue.facilityName,
        nodeType: venue.nodeType,
      })
      router.push(chatUrl(conversationId))
    } catch {
      toast.error('Không thể bắt đầu cuộc trò chuyện')
    } finally {
      setChatStarting(false)
    }
  }

  /**
   * Bump để remount WeeklySchedule — dùng sau khi đặt sân / 409 conflict
   * để grid refetch dữ liệu mới nhất từ BE.
   */
  const [weeklyKey, setWeeklyKey] = useState(0)

  // UC-CUS-07: Bump để remount reviews tab sau khi viết review mới.
  const [reviewRefreshKey, setReviewRefreshKey] = useState(0)

  // Lazy load map when map tab becomes active
  useEffect(() => {
    if (activeTab === 'location') {
      setMapLoaded(true)
    }
  }, [activeTab])

  // Lightbox keyboard navigation
  useEffect(() => {
    if (!lightboxOpen) return

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') {
        setLightboxIndex(prev => (prev > 0 ? prev - 1 : venue.images.length - 1))
      } else if (e.key === 'ArrowRight') {
        setLightboxIndex(prev => (prev < venue.images.length - 1 ? prev + 1 : 0))
      } else if (e.key === 'Escape') {
        setLightboxOpen(false)
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [lightboxOpen, venue.images.length])

  const openLightbox = (index: number) => {
    setLightboxIndex(index)
    setLightboxOpen(true)
  }

  // Get active date formatted as YYYY-MM-DD
  const getSelectedDateString = () => {
    const year = selectedDate.getFullYear()
    const month = String(selectedDate.getMonth() + 1).padStart(2, '0')
    const day = String(selectedDate.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }

  // UC-CUS-01: WeeklySchedule component tự quản lý fetch + tuần hiện tại.
  // VenueDetail chỉ giữ selectedDate/selectedSlot để truyền vào booking CTA.

  /** Handler khi user click một slot AVAILABLE trong weekly grid. */
  const handleSlotPicked = (slotId: number, date: string, startTime?: string, price?: number, endTime?: string) => {
    setSelectedSlot(slotId)
    if (startTime) {
      setSelectedSlotTime(startTime.substring(0, 5))
    }
    if (price !== undefined) {
      setSelectedSlotPrice(price)
    }
    if (endTime) {
      setSelectedSlotEndTime(endTime.substring(0, 5))
    }
    // date là "YYYY-MM-DD" — convert về local Date (00:00) cho booking CTA.
    const [y, m, d] = date.split('-').map(Number)
    setSelectedDate(new Date(y, m - 1, d))
  }

  // Back button helper
  const handleBack = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (typeof window !== 'undefined') {
      if (window.history.length > 1) {
        window.history.back()
      } else {
        window.location.href = '/search'
      }
    }
  }

  const { triggerLoginModal } = useRouteGuard()

  const handleBookSlot = async () => {
    if (!selectedSlot) {
      toast.error('Vui lòng chọn khung giờ trước khi đặt sân')
      return
    }

    // Guest → trigger login modal with current path
    if (!session?.user) {
      const redirect = `/venues/${venue.id}`
      triggerLoginModal(redirect)
      return
    }

    router.push(`/booking/new?venueId=${venue.id}&date=${getSelectedDateString()}&slot=${selectedSlotTime}`)
  }

  // Star renderer helper
  const renderStars = (rating: number, size = 15) => {
    const stars = []
    const fullStars = Math.floor(rating)
    const hasHalf = rating % 1 !== 0
    for (let i = 0; i < 5; i++) {
      if (i < fullStars) {
        stars.push(<IconStar key={i} size={size} className="fill-[#f0a500] text-[#f0a500]" />)
      } else if (i === fullStars && hasHalf) {
        stars.push(<IconStarHalf key={i} size={size} className="fill-[#f0a500] text-[#f0a500]" />)
      } else {
        stars.push(<IconStar key={i} size={size} className="text-gray-300" />)
      }
    }
    return stars
  }

  const getAmenityIcon = (name: string) => {
    switch (name.toLowerCase()) {
      case 'bãi đỗ xe':
      case 'parking':
        return <IconCar className="w-[13px] h-[13px] text-[#1a8a4a] mr-[6px] shrink-0" />
      case 'phòng thay đồ':
      case 'changing room':
        return <IconUser className="w-[13px] h-[13px] text-[#1a8a4a] mr-[6px] shrink-0" />
      case 'nước miễn phí':
      case 'free water':
        return <IconDroplet className="w-[13px] h-[13px] text-[#1a8a4a] mr-[6px] shrink-0" />
      case 'căng tin':
      case 'canteen':
        return <IconCoffee className="w-[13px] h-[13px] text-[#1a8a4a] mr-[6px] shrink-0" />
      case 'wifi':
        return <IconWifi className="w-[13px] h-[13px] text-[#1a8a4a] mr-[6px] shrink-0" />
      default:
        return <IconCircleCheck className="w-[13px] h-[13px] text-[#1a8a4a] mr-[6px] shrink-0" />
    }
  }

  // Reusable Contact Card
  const ContactCard = () => (
    <div className="bg-gray-50 border-[0.5px] border-gray-200 rounded-[8px] p-3 flex flex-col gap-3">
      <div className="flex items-center gap-3">
        {/* Initials avatar */}
        <div className="w-9 h-9 rounded-full bg-[#d4f0e2] text-[#1a8a4a] font-medium text-[12px] flex items-center justify-center uppercase shrink-0">
          {venue.owner.initials || 'HH'}
        </div>
        <div className="leading-tight min-w-0">
          <span className="block text-[13px] font-medium text-gray-750 truncate">{venue.owner.name}</span>
        </div>
      </div>
      <div className="flex gap-2">
        <button
          onClick={handleMessageOwner}
          disabled={chatStarting}
          className="flex-1 flex items-center justify-center gap-1.5 border-[0.5px] border-gray-200 rounded-[20px] py-[5px] text-[12px] font-medium text-gray-600 hover:bg-gray-100 cursor-pointer transition-colors"
        >
          <IconMessageCircle className="w-[13px] h-[13px] text-[#1a8a4a]" />
          <span>{chatStarting ? 'Đang mở...' : 'Nhắn tin'}</span>
        </button>
      </div>
    </div>
  )

  return (
    <div className="w-full min-h-screen bg-gray-50/50 select-none relative flex flex-col font-sans pb-12">
      <div className="w-full max-w-[1440px] mx-auto bg-white shadow-sm md:rounded-2xl md:my-6 overflow-hidden border border-gray-200">
        
        {/* [A] HERO IMAGE SECTION — full width within container */}
        <div 
          onClick={() => openLightbox(0)}
          className="w-full h-[320px] relative overflow-hidden cursor-pointer bg-[#1e4535]"
        >
        {venue.images[activeIndex] ? (
          <img 
            src={venue.images[activeIndex]} 
            alt={venue.name} 
            className="w-full h-full object-cover transition-all duration-300"
          />
        ) : (
          <div className="w-full h-full bg-[#1e4535] flex items-center justify-center">
            <span className="text-white/40 text-[14px]">Hình ảnh sân</span>
          </div>
        )}
        
        {/* Dark overlay */}
        <div className="absolute inset-0 bg-black/32" />

        {/* Circular back button */}
        <button 
          onClick={handleBack}
          className="absolute top-3 left-3 bg-black/40 hover:bg-black/60 text-white w-[34px] h-[34px] rounded-full flex items-center justify-center border-[0.5px] border-white/10 transition-all z-10"
        >
          <IconChevronLeft className="w-5 h-5" />
        </button>

        {/* Maximize Pill Button */}
        <button 
          onClick={(e) => {
            e.stopPropagation()
            openLightbox(activeIndex)
          }}
          className="absolute top-3 right-3 bg-white/20 hover:bg-white/30 text-white px-3 py-1.5 rounded-[20px] flex items-center gap-1.5 text-[12px] font-medium border-[0.5px] border-white/20 transition-all"
        >
          <IconMaximize className="w-4 h-4" />
          <span>Xem ảnh</span>
        </button>

        {/* Counter Badge */}
        <div className="absolute bottom-3 right-3 bg-black/50 text-white px-2.5 py-1 rounded-[20px] text-[12px] font-medium leading-none">
          {activeIndex + 1} / {venue.images.length}
        </div>

        {/* Venue Info overlaid bottom-left */}
        <div className="absolute bottom-3 left-3 text-white right-16">
          <div className="flex flex-wrap items-center gap-2 mb-1.5">
            <div className="inline-flex items-center gap-1 bg-white/20 px-2 py-0.5 rounded-[20px] text-[11px] font-medium border-[0.5px] border-white/10">
              <IconBallFootball className="w-3.5 h-3.5" />
              <span>{venue.sport}</span>
            </div>
            {venue.footballFieldType && (
              <div className="inline-flex items-center gap-1 bg-white/20 px-2 py-0.5 rounded-[20px] text-[11px] font-medium border-[0.5px] border-white/10">
                <span>{fieldTypeMapping[venue.footballFieldType] || venue.footballFieldType}</span>
              </div>
            )}
          </div>
          <h1 className="text-[22px] font-medium leading-tight mb-1 truncate">
            {venue.name}
          </h1>
          <div className="flex items-center gap-2 text-[12px] font-normal text-white/90">
            <div className="flex items-center gap-0.5 text-[#f0a500]">
              <IconStar className="w-3.5 h-3.5 fill-[#f0a500] text-[#f0a500]" />
              <span className="font-medium">
                {venue.reviewCount && venue.reviewCount > 0 ? venue.rating.toFixed(1) : '—'}
              </span>
            </div>
            <span className="text-white/40">|</span>
            <div className="flex items-center gap-1 truncate max-w-xs md:max-w-md">
              <IconMapPin className="w-3.5 h-3.5 shrink-0" />
              <span className="truncate">{venue.address}</span>
            </div>
          </div>
        </div>
      </div>

      {/* [B] THUMBNAIL STRIP — full width */}
      <div className="grid grid-cols-5 gap-[3px] h-[64px] w-full bg-black">
        {venue.images.slice(0, 5).map((img, i) => {
          const isFifth = i === 4
          const hasMore = venue.images.length > 5
          const isActive = i === activeIndex
          const activeStyle = isActive ? { boxShadow: 'inset 0 0 0 2.5px #1a8a4a' } : undefined

          return (
            <div 
              key={i} 
              onClick={() => {
                if (isFifth && hasMore) {
                  openLightbox(4)
                } else {
                  setActiveIndex(i)
                }
              }}
              style={activeStyle}
              className="relative h-[64px] cursor-pointer bg-[#1e4535] overflow-hidden"
            >
              {img ? (
                <img 
                  src={img} 
                  alt={`Thumbnail ${i + 1}`} 
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full bg-[#1e4535] flex items-center justify-center text-[10px] text-white/40">
                  Sân {i + 1}
                </div>
              )}
              {isFifth && hasMore && (
                <div className="absolute inset-0 bg-black/60 flex items-center justify-center text-white font-medium text-[13px]">
                  +{venue.images.length - 4}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* [C] BODY — stretches to fill screen */}
      <div className="w-full px-4 md:px-6 py-5 grid grid-cols-1 lg:grid-cols-[minmax(900px,1fr)_320px] gap-5">
        
        {/* Left column (flex:1) — Tabs + Tab panel content */}
        <div className="flex flex-col min-w-0">
          
          {/* TAB BAR */}
          <div className="grid grid-cols-3 bg-white border-[0.5px] border-gray-200 rounded-[12px] overflow-hidden mb-3.5">
            {[
              { id: 'overview', icon: IconInfoCircle, label: 'Tổng quan' },
              { id: 'slots', icon: IconClock, label: 'Khung giờ' },
              { id: 'services', icon: IconPackage, label: 'Dịch vụ' }
            ].map((tab) => {
              const TabIcon = tab.icon
              const isActive = tab.id === activeTab
              const activeStyle = isActive 
                ? 'text-[#1a8a4a] border-b-[2px] border-[#1a8a4a]' 
                : 'text-gray-400 border-b-[2px] border-transparent hover:bg-gray-50'

              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`py-2 flex flex-col items-center justify-center transition-all ${activeStyle}`}
                  type="button"
                >
                  <TabIcon className="w-[19px] h-[19px] shrink-0" />
                  <span className="text-[11px] font-medium mt-0.5">{tab.label}</span>
                </button>
              )
            })}
          </div>

          {/* PANEL WRAPPER */}
          <div className="bg-white border-[0.5px] border-gray-200 rounded-[12px] p-4 min-h-[300px]">
            
            {/* TAB 1: Tổng quan */}
            {activeTab === 'overview' && (
              <div className="space-y-4">
                
                {/* Info Cards Grid */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-[9px]">
                  <div className="bg-gray-50 rounded-[8px] p-[11px_12px] flex items-center gap-3 border-[0.5px] border-gray-100">
                    <IconClock className="w-5 h-5 text-[#1a8a4a] shrink-0" />
                    <div className="leading-tight">
                      <span className="block text-[11px] text-gray-400 font-normal">Giờ mở cửa</span>
                      <span className="text-[13px] font-medium text-gray-700">{venue.openTime} – {venue.closeTime}</span>
                    </div>
                  </div>
                  <div className="bg-gray-50 rounded-[8px] p-[11px_12px] flex items-center gap-3 border-[0.5px] border-gray-100">
                    <IconCategory className="w-5 h-5 text-[#1a8a4a] shrink-0" />
                    <div className="leading-tight">
                      <span className="block text-[11px] text-gray-400 font-normal">Loại sân</span>
                      <span className="text-[13px] font-medium text-gray-700">{venue.sport}</span>
                    </div>
                  </div>
                  <div className="bg-gray-50 rounded-[8px] p-[11px_12px] flex items-center gap-3 border-[0.5px] border-gray-100">
                    <IconCircleCheck className="w-5 h-5 text-[#1a8a4a] shrink-0" />
                    <div className="leading-tight">
                      <span className="block text-[11px] text-gray-400 font-normal">Trạng thái</span>
                      <span className="text-[13px] font-medium text-[#1a8a4a]">Hoạt động</span>
                    </div>
                  </div>
                </div>

                {/* Description Box */}
                <div className="bg-gray-50 rounded-[8px] p-[11px_13px] border-[0.5px] border-gray-100">
                  <p className="text-[13px] text-gray-500 leading-[1.6] font-normal">
                    {venue.description || 'Chưa có mô tả cho sân này.'}
                  </p>
                </div>

                {/* Amenities */}
                <div className="space-y-2 pt-1">
                  <span className="block text-[11px] font-medium tracking-[0.5px] uppercase text-gray-400">
                    TIỆN ÍCH
                  </span>
                  <div className="flex flex-wrap gap-2">
                    {venue.amenities && venue.amenities.length > 0 ? (
                      venue.amenities.map((amenity, i) => (
                        <div 
                          key={i} 
                          className="inline-flex items-center bg-gray-50 border-[0.5px] border-gray-200 rounded-[20px] px-3 py-1.5 text-[12px] font-normal text-gray-600"
                        >
                          {getAmenityIcon(amenity)}
                          <span>{amenity}</span>
                        </div>
                      ))
                    ) : (
                      <div className="text-[12px] text-gray-400 py-1 italic">Chưa có thông tin tiện ích.</div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* TAB 2: Khung giờ — UC-CUS-01 weekly grid */}
            {activeTab === 'slots' && (
              <div className="space-y-3">
                <WeeklySchedule
                  key={weeklyKey}
                  stadiumId={venue.id}
                  onSlotSelect={handleSlotPicked}
                />
              </div>
            )}

            {/* TAB 3: Dịch vụ */}
            {activeTab === 'services' && (
              <div className="space-y-3">
                <span className="block text-[11px] font-medium tracking-[0.5px] uppercase text-gray-400">
                  DỊCH VỤ & PHỤ KIỆN
                </span>
                <div className="flex flex-col gap-2">
                  {venue.services && venue.services.length > 0 ? (
                    venue.services.map((srv, i) => (
                      <div 
                        key={i}
                        className="bg-gray-50 border-[0.5px] border-gray-100 rounded-[8px] p-[10px_13px] flex justify-between items-center"
                      >
                        <div className="leading-tight">
                          <span className="block text-[13px] font-medium text-gray-700">{srv.name}</span>
                          <span className="text-[11px] text-gray-400 font-normal">Còn {srv.stock} sản phẩm</span>
                        </div>
                        <span className="text-[14px] font-medium text-[#1a8a4a]">
                          {srv.price.toLocaleString('vi-VN')}đ
                        </span>
                      </div>
                    ))
                  ) : (
                    <div className="py-8 text-center text-[12px] text-gray-450">
                      Không có dịch vụ đi kèm.
                    </div>
                  )}
                </div>
              </div>
            )}



          </div>
        </div>

        {/* Right column (320px) — Sticky sidebar */}
        <div className="w-full lg:w-[320px] flex flex-col gap-[14px] shrink-0">

          <div className="sticky top-5 flex flex-col gap-[14px]">
            
            {/* CARD 1: Booking card */}
            <div className="bg-white border-[0.5px] border-gray-200 rounded-[12px] p-4 flex flex-col gap-3.5 shadow-none">
              
              {selectedSlot !== null ? (
                <div>
                  <span className="block text-[11px] text-gray-400 font-normal">Giá thuê khung giờ đã chọn</span>
                  <div className="flex items-baseline gap-0.5 mt-0.5">
                    <span className="text-[26px] font-bold text-[#1a8a4a] leading-none">
                      {selectedSlotPrice?.toLocaleString('vi-VN')}đ
                    </span>
                    <span className="text-[13px] text-gray-400 font-normal">/khung giờ</span>
                  </div>
                  <span className="block text-[12px] text-gray-600 font-semibold mt-1">
                    Đã chọn: {selectedSlotTime} - {selectedSlotEndTime}, ngày {getSelectedDateString().split('-').reverse().join('/')}
                  </span>
                </div>
              ) : (
                <div>
                  <span className="block text-[11px] text-gray-400 font-normal">Giá thuê tiêu chuẩn</span>
                  <div className="flex items-baseline gap-0.5 mt-0.5">
                    <span className="text-[26px] font-semibold text-[#1a8a4a] leading-none">
                      {venue.pricePerHour.toLocaleString('vi-VN')}đ
                    </span>
                    <span className="text-[13px] text-gray-400 font-normal">/giờ</span>
                  </div>
                </div>
              )}

              {/* Rating chip */}
              {venue.reviewCount > 0 ? (
                <div className="flex items-center gap-1.5 bg-[#fffbeb] border-[0.5px] border-[#e8c84a] rounded-[8px] px-3 py-1.5 select-none">
                  <IconStar className="w-[14px] h-[14px] fill-[#f0a500] text-[#f0a500]" />
                  <span className="text-[14px] font-medium text-[#7a5800] leading-none">
                    {venue.rating.toFixed(1)} / 5.0
                  </span>
                  <span className="text-[11px] text-[#7a5800]/70 font-normal leading-none">
                    • {venue.reviewCount} đánh giá
                  </span>
                </div>
              ) : (
                <div className="flex items-center gap-1 bg-gray-50 border-[0.5px] border-gray-200 rounded-[8px] px-3 py-1.5 select-none">
                  <span className="text-[12px] text-gray-400 font-medium leading-none">Chưa có đánh giá</span>
                </div>
              )}

              {/* CTA Booking Button — UC-CUS-01 single booking */}
              <button
                onClick={handleBookSlot}
                disabled={selectedSlot === null || bookingSubmitting}
                className="w-full flex items-center justify-center gap-1.5 bg-[#1a8a4a] hover:bg-[#157a3e] disabled:bg-gray-300 disabled:cursor-not-allowed text-white font-medium text-[14px] py-3 rounded-[8px] transition-colors border-none"
                type="button"
              >
                <IconCalendarPlus className="w-[18px] h-[18px]" />
                <span>
                  {bookingSubmitting
                    ? 'Đang đặt sân…'
                    : selectedSlot !== null
                      ? 'Đặt sân ngay'
                      : 'Chọn khung giờ để đặt'}
                </span>
              </button>

              <div className="border-t-[0.5px] border-gray-200 pt-3 flex flex-col gap-2">
                <span className="block text-[11px] font-semibold tracking-[0.5px] uppercase text-gray-400">
                  THÔNG TIN NHANH
                </span>
                <div className="flex items-center justify-between text-[13px]">
                  <span className="text-gray-400">Loại sân</span>
                  <span className="text-gray-700 font-medium">{venue.sport}</span>
                </div>
                <div className="flex items-center justify-between text-[13px]">
                  <span className="text-gray-400">Trạng thái</span>
                  <span className="bg-[#e8f7ee] text-[#0d5c2e] text-[12px] font-medium px-2.5 py-0.5 rounded-[20px] select-none">
                    Hoạt động
                  </span>
                </div>
              </div>

            </div>

            {/* CARD 2: Contact card */}
            <div className="bg-white border-[0.5px] border-gray-200 rounded-[12px] p-4 flex flex-col gap-2 shadow-none">
              <span className="block text-[11px] font-medium tracking-[0.5px] uppercase text-gray-400 select-none mb-1">
                LIÊN HỆ CHỦ SÂN
              </span>
              <ContactCard />
            </div>

          </div>

        </div>

      </div>

      </div> {/* Closing the max-w-5xl container */}

      {/* LIGHTBOX (full-screen image viewer) */}
      {lightboxOpen && (
        <div 
          className="fixed inset-0 z-50 flex flex-col items-center justify-center p-4 select-none"
          style={{ backgroundColor: 'rgba(0, 0, 0, 0.88)' }}
        >
          <div className="relative max-w-[720px] w-full flex flex-col items-center">
            
            {/* Top-right close button */}
            <button 
              onClick={() => setLightboxOpen(false)}
              className="absolute -top-12 right-2 w-9 h-9 rounded-full bg-black/60 hover:bg-black/80 text-white flex items-center justify-center border border-white/10 transition-colors z-20"
              type="button"
            >
              <IconX className="w-5 h-5" />
            </button>

            {/* Image display */}
            <div className="w-full h-[400px] bg-black rounded-[12px] overflow-hidden flex items-center justify-center border border-white/10 relative">
              {venue.images[lightboxIndex] ? (
                <img 
                  src={venue.images[lightboxIndex]} 
                  alt={`Viewer ${lightboxIndex + 1}`} 
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full bg-[#1e4535] flex items-center justify-center text-white/50 text-[14px]">
                  Hình ảnh sân
                </div>
              )}
            </div>

            {/* Caption */}
            <div className="mt-3 text-center">
              <p className="text-[12px] font-medium text-white/70">
                Ảnh {lightboxIndex + 1} / {venue.images.length} — {IMAGE_LABELS[lightboxIndex] || 'Chi tiết hình ảnh sân'}
              </p>
            </div>

            {/* Navigation buttons */}
            <div className="flex gap-4 justify-center mt-4">
              <button 
                onClick={() => setLightboxIndex(prev => (prev > 0 ? prev - 1 : venue.images.length - 1))}
                className="flex items-center gap-1.5 bg-white/10 hover:bg-white/20 active:bg-white/30 text-white text-[13px] font-medium px-4 py-2 rounded-[8px] transition-all border border-white/10"
                type="button"
              >
                <IconArrowLeft className="w-4 h-4" />
                <span>Trước</span>
              </button>
              <button 
                onClick={() => setLightboxIndex(prev => (prev < venue.images.length - 1 ? prev + 1 : 0))}
                className="flex items-center gap-1.5 bg-white/10 hover:bg-white/20 active:bg-white/30 text-white text-[13px] font-medium px-4 py-2 rounded-[8px] transition-all border border-white/10"
                type="button"
              >
                <span>Sau</span>
                <IconArrowRight className="w-4 h-4" />
              </button>
            </div>

            {/* Dot indicators */}
            <div className="flex gap-1.5 justify-center mt-5">
              {venue.images.map((_, i) => (
                <button 
                  key={i} 
                  onClick={() => setLightboxIndex(i)}
                  className={`h-[7px] rounded-full cursor-pointer transition-all duration-300 ${
                    i === lightboxIndex 
                      ? 'w-[20px] bg-white' 
                      : 'w-[7px] bg-white/30 hover:bg-white/50'
                  }`}
                  type="button"
                />
              ))}
            </div>

          </div>
        </div>
      )}

    </div>
  )
}
