'use client'

import { useState, useEffect } from 'react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { useRouter, useSearchParams } from 'next/navigation'
import { useRouteGuard } from '@/components/shared/RouteGuard'
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
import type { StadiumComplexDto, FacilityDto, CourtWithSlotsDto } from '@/types/complex'
import type { ReviewDto } from '@/lib/api/venue'
import { getComplexReviews } from '@/lib/api/complex'
import { Skeleton } from '@/components/ui/skeleton'
import FacilityTabs from './FacilityTabs'
import { toast } from 'sonner'
import { chatUrl, createContextualConversation } from '@/lib/contextual-chat'

// Lazy load map component to prevent SSR issue
const VenueMap = dynamic(() => import('../venues/VenueMap'), {
  ssr: false,
  loading: () => (
    <div className="h-[180px] bg-gray-100 flex items-center justify-center rounded-[8px] animate-pulse border-[0.5px] border-gray-200">
      <span className="text-[12px] text-gray-400">Đang tải bản đồ...</span>
    </div>
  )
})

interface ComplexDetailProps {
  complex: StadiumComplexDto
  facilities: FacilityDto[]
  activeFacilityId: number | null
  setActiveFacilityId: (id: number | null) => void
  courts: CourtWithSlotsDto[]
  courtsLoading: boolean
}

export default function ComplexDetail({
  complex,
  facilities,
  activeFacilityId,
  setActiveFacilityId,
  courts,
  courtsLoading
}: ComplexDetailProps) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { data: session } = useSession()
  const { triggerLoginModal } = useRouteGuard()

  // Tự động chuyển sang tab 'courts' (Sân lẻ) nếu có sportTypeId trên URL hoặc có param tab=courts
  const sportTypeIdParam = searchParams.get('sportTypeId')
  const tabParam = searchParams.get('tab')
  const defaultTab = sportTypeIdParam || tabParam === 'courts'
    ? 'courts'
    : 'overview'

  const [activeIndex, setActiveIndex] = useState(0)
  const [activeTab, setActiveTab] = useState<string>(defaultTab)
  const [lightboxOpen, setLightboxOpen] = useState(false)
  const [lightboxIndex, setLightboxIndex] = useState(0)
  const [mapLoaded, setMapLoaded] = useState(false)
  const [reviews, setReviews] = useState<ReviewDto[]>([])
  const [reviewsLoading, setReviewsLoading] = useState(false)
  const [reviewsLoadingMore, setReviewsLoadingMore] = useState(false)
  const [reviewsError, setReviewsError] = useState(false)
  const [reviewPage, setReviewPage] = useState(0)
  const [reviewHasMore, setReviewHasMore] = useState(false)
  const [reviewsFetched, setReviewsFetched] = useState(false)
  const [chatStarting, setChatStarting] = useState(false)

  const handleMessageOwner = async () => {
    if (!session) {
      triggerLoginModal(window.location.pathname)
      return
    }
    if (!complex.ownerUserId) {
      toast.error('Không tìm thấy tài khoản chủ sân')
      return
    }
    try {
      setChatStarting(true)
      const conversationId = await createContextualConversation(complex.ownerUserId, {
        action: 'stadium_referral',
        stadiumId: complex.complexId,
        stadiumName: complex.name,
        complexId: complex.complexId,
        complexName: complex.name,
        nodeType: 'COMPLEX',
      })
      router.push(chatUrl(conversationId))
    } catch {
      toast.error('Không thể bắt đầu cuộc trò chuyện')
    } finally {
      setChatStarting(false)
    }
  }

  // Load review list the first time the "Đánh giá" tab is opened.
  useEffect(() => {
    if (activeTab !== 'reviews' || reviewsFetched) return

    let cancelled = false
    setReviewsLoading(true)
    setReviewsError(false)

    getComplexReviews(complex.complexId, 0, 5)
      .then((res) => {
        if (cancelled) return
        setReviews(res.content)
        setReviewPage(0)
        setReviewHasMore(!res.last)
        setReviewsFetched(true)
      })
      .catch(() => {
        if (!cancelled) setReviewsError(true)
      })
      .finally(() => {
        if (!cancelled) setReviewsLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [activeTab, complex.complexId, reviewsFetched])

  const loadMoreReviews = async () => {
    setReviewsLoadingMore(true)
    try {
      const nextPage = reviewPage + 1
      const res = await getComplexReviews(complex.complexId, nextPage, 5)
      setReviews((prev) => [...prev, ...res.content])
      setReviewPage(nextPage)
      setReviewHasMore(!res.last)
    } catch {
      setReviewHasMore(false)
    } finally {
      setReviewsLoadingMore(false)
    }
  }

  // Re-sync activeTab whenever the URL params that drive defaultTab change.
  // Next.js does not remount this component when only the query string changes
  // (e.g. navigating to the same complex again with a different sportTypeId),
  // so relying on the useState initializer alone would leave the tab stuck.
  useEffect(() => {
    setActiveTab(defaultTab)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sportTypeIdParam, tabParam])

  // Collect all images
  const allImages: string[] = []
  if (complex.coverImageUrl) {
    allImages.push(complex.coverImageUrl)
  }
  if (complex.images && complex.images.length > 0) {
    complex.images.forEach(img => {
      if (img.imageUrl && !allImages.includes(img.imageUrl)) {
        allImages.push(img.imageUrl)
      }
    })
  }
  const displayImages = allImages.length > 0 ? allImages : ['/placeholder-venue.jpg']

  // Price formatting
  const formatPrice = (p: number | null | undefined) => {
    return p ? p.toLocaleString('vi-VN') : '0'
  }

  const priceRange = complex.minPrice && complex.maxPrice
    ? `${formatPrice(complex.minPrice)}đ – ${formatPrice(complex.maxPrice)}đ`
    : complex.minPrice
      ? `${formatPrice(complex.minPrice)}đ`
      : '150.000đ'

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
        setLightboxIndex(prev => (prev > 0 ? prev - 1 : displayImages.length - 1))
      } else if (e.key === 'ArrowRight') {
        setLightboxIndex(prev => (prev < displayImages.length - 1 ? prev + 1 : 0))
      } else if (e.key === 'Escape') {
        setLightboxOpen(false)
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [lightboxOpen, displayImages.length])

  const openLightbox = (index: number) => {
    setLightboxIndex(index)
    setLightboxOpen(true)
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

  const getOwnerInitials = () => {
    if (!complex.ownerName) return '??'
    const words = complex.ownerName.split(' ')
    return words.slice(-2).map(w => w[0]).join('').toUpperCase()
  }

  const handleSidebarSelectCourt = () => {
    setActiveTab('courts')
    const element = document.getElementById('complex-tab-content')
    if (element) {
      element.scrollIntoView({ behavior: 'smooth' })
    }
  }

  // Reusable Contact Card
  const ContactCard = () => (
    <div className="bg-gray-50 border-[0.5px] border-gray-200 rounded-[8px] p-3 flex flex-col gap-3">
      <div className="flex items-center gap-3">
        <div className="w-9 h-9 rounded-full bg-[#d4f0e2] text-[#1a8a4a] font-medium text-[12px] flex items-center justify-center uppercase shrink-0">
          {getOwnerInitials()}
        </div>
        <div className="leading-tight min-w-0">
          <span className="block text-[13px] font-medium text-gray-755 truncate">{complex.ownerName || 'N/A'}</span>
        </div>
      </div>
      <div className="flex gap-2">
        <button
          onClick={handleMessageOwner}
          disabled={chatStarting}
          className="flex-1 flex items-center justify-center gap-1.5 border-[0.5px] border-gray-200 rounded-[20px] py-[5px] text-[12px] font-medium text-gray-600 hover:bg-gray-100 cursor-pointer transition-colors"
          type="button"
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

        {/* [A] HERO IMAGE SECTION */}
        <div
          onClick={() => openLightbox(activeIndex)}
          className="w-full h-[320px] relative overflow-hidden cursor-pointer bg-[#1e4535]"
        >
          {displayImages[activeIndex] ? (
            <img
              src={displayImages[activeIndex]}
              alt={complex.name}
              className="w-full h-full object-cover transition-all duration-300"
            />
          ) : (
            <div className="w-full h-full bg-[#1e4535] flex items-center justify-center">
              <span className="text-white/40 text-[14px]">Hình ảnh tổ hợp</span>
            </div>
          )}

          {/* Dark overlay */}
          <div className="absolute inset-0 bg-black/30" />

          {/* Circular back button */}
          <button
            onClick={handleBack}
            className="absolute top-3 left-3 bg-black/40 hover:bg-black/60 text-white w-[34px] h-[34px] rounded-full flex items-center justify-center border-[0.5px] border-white/10 transition-all z-10"
            type="button"
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
            type="button"
          >
            <IconMaximize className="w-4 h-4" />
            <span>Xem ảnh</span>
          </button>

          {/* Counter Badge */}
          <div className="absolute bottom-3 right-3 bg-black/50 text-white px-2.5 py-1 rounded-[20px] text-[12px] font-medium leading-none">
            {activeIndex + 1} / {displayImages.length}
          </div>

          {/* Info overlaid bottom-left */}
          <div className="absolute bottom-3 left-3 text-white right-16">
            <div className="inline-flex items-center gap-1 bg-white/20 px-2 py-0.5 rounded-[20px] text-[11px] font-medium border-[0.5px] border-white/10 mb-1.5">
              <IconBallFootball className="w-3.5 h-3.5" />
              <span>{complex.sportTypes?.map(s => s.sportName).join(', ') || 'Thể thao'}</span>
            </div>
            <h1 className="text-[22px] font-medium leading-tight mb-1 truncate">
              {complex.name}
            </h1>
            <div className="flex items-center gap-2 text-[12px] font-normal text-white/90">
              <div className="flex items-center gap-0.5 text-[#f0a500]">
                <IconStar className="w-3.5 h-3.5 fill-[#f0a500] text-[#f0a500]" />
                <span className="font-medium">
                  {complex.reviewCount && complex.reviewCount > 0 ? (complex.averageRating || 5.0).toFixed(1) : '—'}
                </span>
              </div>
              <span className="text-white/40">|</span>
              <div className="flex items-center gap-1 truncate max-w-xs md:max-w-md">
                <IconMapPin className="w-3.5 h-3.5 shrink-0" />
                <span className="truncate">{complex.address}</span>
              </div>
            </div>
          </div>
        </div>

        {/* [B] THUMBNAIL STRIP */}
        <div className="grid grid-cols-5 gap-[3px] h-[64px] w-full bg-black">
          {displayImages.slice(0, 5).map((img, i) => {
            const isFifth = i === 4
            const hasMore = displayImages.length > 5
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
                    +{displayImages.length - 4}
                  </div>
                )}
              </div>
            )
          })}
        </div>

        {/* [C] BODY */}
        <div className="w-full px-4 md:px-6 py-5 grid grid-cols-1 lg:grid-cols-[minmax(900px,1fr)_320px] gap-5">

          {/* Left Column (flex:1) — Tabs + Tab panel content */}
          <div className="flex flex-col min-w-0" id="complex-tab-content">

            {/* TAB BAR */}
            <div className="grid grid-cols-4 bg-white border-[0.5px] border-gray-200 rounded-[12px] overflow-hidden mb-3.5">
              {[
                { id: 'overview', icon: IconInfoCircle, label: 'Tổng quan' },
                { id: 'courts', icon: IconBallFootball, label: 'Sân lẻ' },
                { id: 'location', icon: IconMap2, label: 'Vị trí' },
                { id: 'reviews', icon: IconMessageCircle, label: 'Đánh giá' }
              ].map((tab) => {
                const TabIcon = tab.icon
                const isActive = tab.id === activeTab
                const activeStyle = isActive
                  ? 'text-[#1a8a4a] border-b-[2px] border-[#1a8a4a] bg-emerald-50/10'
                  : 'text-gray-400 border-b-[2px] border-transparent hover:bg-gray-50'

                return (
                  <button
                    key={tab.id}
                    onClick={() => setActiveTab(tab.id)}
                    className={`py-2.5 flex flex-col items-center justify-center transition-all ${activeStyle}`}
                    type="button"
                  >
                    <TabIcon className="w-[19px] h-[19px] shrink-0" />
                    <span className="text-[11px] font-semibold mt-0.5">{tab.label}</span>
                  </button>
                )
              })}
            </div>

            {/* PANEL WRAPPER */}
            <div className="bg-white border-[0.5px] border-gray-200 rounded-[12px] p-4 min-h-[300px]">

              {/* TAB 1: Tổng quan */}
              {activeTab === 'overview' && (
                <div className="space-y-4">
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-[9px]">
                    <div className="bg-gray-50 rounded-[8px] p-[11px_12px] flex items-center gap-3 border-[0.5px] border-gray-100">
                      <IconMapPin className="w-5 h-5 text-[#1a8a4a] shrink-0" />
                      <div className="leading-tight">
                        <span className="block text-[11px] text-gray-400 font-normal">Địa chỉ</span>
                        <span className="text-[13px] font-medium text-gray-700">{complex.address}</span>
                      </div>
                    </div>
                    <div className="bg-gray-50 rounded-[8px] p-[11px_12px] flex items-center gap-3 border-[0.5px] border-gray-100">
                      <IconCategory className="w-5 h-5 text-[#1a8a4a] shrink-0" />
                      <div className="leading-tight">
                        <span className="block text-[11px] text-gray-400 font-normal">Các môn thể thao</span>
                        <span className="text-[13px] font-medium text-gray-700">
                          {complex.sportTypes?.map(s => s.sportName).join(', ') || 'N/A'}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="bg-gray-50 rounded-[8px] p-[11px_13px] border-[0.5px] border-gray-100">
                    <p className="text-[13px] text-gray-500 leading-[1.6] font-normal">
                      {complex.description || 'Chưa có mô tả cho tổ hợp này.'}
                    </p>
                  </div>

                  {/* Amenities */}
                  <div className="space-y-2 pt-1">
                    <span className="block text-[11px] font-medium tracking-[0.5px] uppercase text-gray-400">
                      TIỆN ÍCH
                    </span>
                    <div className="flex flex-wrap gap-2">
                      {complex.amenities && complex.amenities.length > 0 ? (
                        complex.amenities.map((amenity, i) => (
                          <div
                            key={i}
                            className="inline-flex items-center bg-gray-50 border-[0.5px] border-gray-200 rounded-[20px] px-3 py-1.5 text-[12px] font-normal text-gray-600"
                          >
                            {getAmenityIcon(amenity.name)}
                            <span>{amenity.name}</span>
                          </div>
                        ))
                      ) : (
                        <div className="text-[12px] text-gray-400 py-1 italic">Chưa có thông tin tiện ích.</div>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {/* TAB 2: Danh sách Sân lẻ (Courts) */}
              {activeTab === 'courts' && (
                <div className="space-y-6">
                  {/* Facility selection tabs */}
                  {facilities.length > 0 && (
                    <div className="space-y-2">
                      <span className="block text-[11px] font-bold tracking-[0.5px] uppercase text-gray-400">
                        CHỌN KHU VỰC SÂN
                      </span>
                      <FacilityTabs
                        facilities={facilities}
                        selectedId={activeFacilityId}
                        onSelect={(id) => setActiveFacilityId(id)}
                      />
                    </div>
                  )}

                  {/* Courts list */}
                  <div className="space-y-3">
                    <h3 className="text-sm font-bold text-gray-700">
                      Danh sách sân lẻ {courts.length > 0 && `(${courts.length} sân đang hoạt động)`}
                    </h3>

                    {courtsLoading ? (
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        {[1, 2].map((i) => (
                          <div key={i} className="h-32 bg-gray-50 border border-gray-150 rounded-[12px] animate-pulse" />
                        ))}
                      </div>
                    ) : courts.length === 0 ? (
                      <div className="text-center py-10 bg-gray-50 rounded-[12px] border border-dashed border-gray-200">
                        <IconBallFootball className="w-10 h-10 text-gray-350 mx-auto mb-2" />
                        <p className="text-sm text-gray-400 font-medium">Không có sân lẻ nào phù hợp với loại sân bạn tìm hoặc chưa được cấu hình.</p>
                      </div>
                    ) : (
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        {courts.map((court) => {
                          // stadiumStatus riêng vẫn AVAILABLE khi bảo trì đến từ Facility/Complex cha
                          // (cascade) hoặc MaintenanceSchedule theo khung ngày.
                          const isAvailable = court.stadiumStatus === 'AVAILABLE' && !court.underMaintenanceToday
                          const isMaintenance = court.stadiumStatus === 'MAINTENANCE' || (court.stadiumStatus === 'AVAILABLE' && !!court.underMaintenanceToday)
                          return (
                            <div
                              key={court.stadiumId}
                              className="bg-white border border-gray-200 rounded-[12px] p-4 flex flex-col justify-between shadow-xs hover:border-[#1a8a4a]/40 transition-all group"
                            >
                              <div>
                                <div className="flex justify-between items-start mb-2">
                                  <h4 className="text-[14px] font-bold text-gray-850 group-hover:text-[#1a8a4a] transition-colors leading-tight">
                                    {court.stadiumName}
                                  </h4>
                                  <span
                                    className={`text-[9px] font-bold px-2 py-0.5 rounded-[20px] tracking-wide select-none ${isAvailable
                                        ? 'bg-emerald-50 text-emerald-700 border border-emerald-100'
                                        : isMaintenance
                                          ? 'bg-amber-50 text-amber-700 border border-amber-100'
                                          : 'bg-red-50 text-red-700 border border-red-100'
                                      }`}
                                  >
                                    {isAvailable ? 'CÒN TRỐNG' : isMaintenance ? 'ĐANG BẢO TRÌ' : 'TẠM ĐÓNG'}
                                  </span>
                                </div>
                                <p className="text-[12px] text-gray-400 line-clamp-2 font-normal leading-relaxed mb-3">
                                  {court.description || 'Sân tiêu chuẩn cao phục vụ đặt chỗ thi đấu, tập luyện hàng ngày.'}
                                </p>
                              </div>
                              <div className="flex gap-2 items-center">
                                {/* MAINTENANCE vẫn cho xem lịch tuần — chỉ chặn đặt đúng slot đang
                                    bảo trì (backend tự chặn), khách vẫn cần xem để đặt slot/ngày khác.
                                    Chỉ CLOSED (đóng cửa hẳn) mới thực sự không cho vào. */}
                                {!isAvailable && !isMaintenance ? (
                                  <button
                                    disabled
                                    className="w-full flex items-center justify-center bg-gray-100 text-gray-400 text-[12px] font-semibold py-2 rounded-[8px] cursor-not-allowed"
                                  >
                                    Tạm đóng
                                  </button>
                                ) : (
                                  <Link
                                    href={`/venues/${court.stadiumId}`}
                                    className={
                                      isAvailable
                                        ? 'w-full flex items-center justify-center bg-emerald-50 hover:bg-[#1a8a4a]/10 text-[#1a8a4a] border border-[#1a8a4a]/20 text-[12px] font-semibold py-2 rounded-[8px] transition-all'
                                        : 'w-full flex items-center justify-center bg-amber-50 hover:bg-amber-100 text-amber-700 border border-amber-200 text-[12px] font-semibold py-2 rounded-[8px] transition-all'
                                    }
                                  >
                                    {isAvailable ? 'Xem lịch & đặt' : 'Xem lịch (đang bảo trì)'}
                                  </Link>
                                )}
                              </div>
                            </div>
                          )
                        })}
                      </div>
                    )}
                  </div>
                </div>
              )}


              {/* TAB 4: Vị trí */}
              {activeTab === 'location' && (
                <div className="space-y-4">
                  <div className="border-[0.5px] border-gray-200 rounded-[8px] overflow-hidden bg-gray-55 text-center flex flex-col">
                    {mapLoaded && complex.latitude && complex.longitude && (
                      <VenueMap
                        latitude={complex.latitude}
                        longitude={complex.longitude}
                        venueName={complex.name}
                        height="h-[300px]"
                      />
                    )}
                  </div>
                </div>
              )}

              {/* TAB 5: Đánh giá */}
              {activeTab === 'reviews' && (
                <div className="space-y-4">
                  <div className="bg-gray-50 border-[0.5px] border-gray-200 rounded-[8px] p-4 flex flex-col md:flex-row items-center gap-5">
                    <div className="text-center shrink-0 min-w-[120px]">
                      <span className="block text-[36px] font-extrabold text-gray-800 leading-none">
                        {complex.reviewCount && complex.reviewCount > 0 ? (complex.averageRating || 5.0).toFixed(1) : '—'}
                      </span>
                      <div className="flex items-center justify-center gap-0.5 mt-2">
                        {renderStars(complex.reviewCount && complex.reviewCount > 0 ? complex.averageRating || 5.0 : 0.0, 14)}
                      </div>
                      <span className="block text-[11px] text-gray-400 font-normal mt-1.5">
                        {complex.reviewCount || 0} lượt đánh giá
                      </span>
                    </div>
                    <div className="flex-1 text-[13px] text-gray-500 font-normal leading-relaxed">
                      Điểm đánh giá được tổng hợp tự động từ tất cả các giao dịch đặt sân thành công tại các sân con trong tổ hợp.
                      Chúng tôi cam kết hiển thị phản hồi thực tế và minh bạch nhất từ phía khách hàng.
                    </div>
                  </div>

                  {reviewsLoading ? (
                    <div className="space-y-3">
                      {Array.from({ length: 3 }).map((_, i) => (
                        <Skeleton key={i} className="h-[92px] rounded-[8px]" />
                      ))}
                    </div>
                  ) : reviewsError ? (
                    <div className="flex flex-col items-center justify-center py-10 text-center">
                      <p className="text-gray-400 text-[13px]">Không tải được đánh giá, vui lòng thử lại sau.</p>
                    </div>
                  ) : reviews.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-10 text-center">
                      <IconMessageCircle className="w-9 h-9 text-gray-300 mb-2" />
                      <p className="text-gray-400 text-[13px]">Chưa có đánh giá nào cho tổ hợp này</p>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {reviews.map((review) => (
                        <div key={review.reviewId} className="bg-white border-[0.5px] border-gray-200 rounded-[8px] p-4">
                          <div className="flex items-start justify-between gap-3">
                            <div className="flex items-center gap-2.5 min-w-0">
                              {review.userAvatar ? (
                                <img
                                  src={review.userAvatar}
                                  alt={review.userName}
                                  className="w-9 h-9 rounded-full object-cover shrink-0"
                                />
                              ) : (
                                <div className="w-9 h-9 rounded-full bg-gray-100 flex items-center justify-center text-[13px] font-semibold text-gray-500 shrink-0">
                                  {review.userName.charAt(0).toUpperCase()}
                                </div>
                              )}
                              <div className="min-w-0">
                                <p className="text-[13px] font-semibold text-gray-800 truncate">{review.userName}</p>
                                <p className="text-[11px] text-gray-400">
                                  {new Date(review.createdAt).toLocaleDateString('vi-VN')}
                                </p>
                              </div>
                            </div>
                            <div className="flex items-center gap-0.5 shrink-0">
                              {renderStars(review.ratingScore, 13)}
                            </div>
                          </div>
                          {review.comment && (
                            <p className="text-[13px] text-gray-600 mt-2.5 leading-relaxed">{review.comment}</p>
                          )}
                          {review.ownerResponse && (
                            <div className="mt-2.5 bg-gray-50 rounded-[6px] p-2.5">
                              <p className="text-[11px] font-semibold text-gray-500 mb-0.5">Phản hồi từ chủ sân</p>
                              <p className="text-[12px] text-gray-600">{review.ownerResponse}</p>
                            </div>
                          )}
                        </div>
                      ))}
                      {reviewHasMore && (
                        <button
                          type="button"
                          onClick={loadMoreReviews}
                          disabled={reviewsLoadingMore}
                          className="w-full text-center text-[13px] text-primary font-semibold py-2 transition-opacity disabled:opacity-50"
                        >
                          {reviewsLoadingMore ? 'Đang tải...' : 'Xem thêm đánh giá'}
                        </button>
                      )}
                    </div>
                  )}
                </div>
              )}

            </div>
          </div>

          {/* Right Column (320px) — Sticky Sidebar */}
          <div className="w-full lg:w-[320px] flex flex-col gap-[14px] shrink-0">
            <div className="sticky top-5 flex flex-col gap-[14px]">

              {/* CARD: Contact & General Info Card */}
              <div className="bg-white border-[0.5px] border-gray-200 rounded-[12px] p-4 flex flex-col gap-4 shadow-none">
                <span className="block text-[11px] font-semibold tracking-[0.5px] uppercase text-gray-400 select-none">
                  LIÊN HỆ CHỦ SÂN
                </span>
                <ContactCard />

                <div className="border-t-[0.5px] border-gray-200 pt-3 flex flex-col gap-2">
                  <span className="block text-[11px] font-semibold tracking-[0.5px] uppercase text-gray-400">
                    THÔNG TIN CHUNG
                  </span>
                  <div className="flex items-center justify-between text-[13px]">
                    <span className="text-gray-400">Môn thể thao</span>
                    <span className="text-gray-700 font-semibold truncate max-w-[150px]">
                      {complex.sportTypes?.map(s => s.sportName).join(', ') || 'N/A'}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-[13px]">
                    <span className="text-gray-400">Trạng thái</span>
                    <span className="bg-[#e8f7ee] text-[#0d5c2e] text-[12px] font-bold px-2.5 py-0.5 rounded-[20px] select-none">
                      Hoạt động
                    </span>
                  </div>
                </div>
              </div>

            </div>
          </div>

        </div>

      </div>

      {/* LIGHTBOX viewer */}
      {lightboxOpen && (
        <div
          className="fixed inset-0 z-50 flex flex-col items-center justify-center p-4 select-none"
          style={{ backgroundColor: 'rgba(0, 0, 0, 0.88)' }}
          onClick={() => setLightboxOpen(false)}
        >
          <div className="relative max-w-[720px] w-full flex flex-col items-center" onClick={(e) => e.stopPropagation()}>
            <button
              onClick={() => setLightboxOpen(false)}
              className="absolute -top-12 right-2 w-9 h-9 rounded-full bg-black/60 hover:bg-black/80 text-white flex items-center justify-center border border-white/10 transition-colors z-20"
              type="button"
            >
              <IconX className="w-5 h-5" />
            </button>

            <div className="w-full h-[400px] bg-black rounded-[12px] overflow-hidden flex items-center justify-center border border-white/10 relative">
              {displayImages[lightboxIndex] ? (
                <img
                  src={displayImages[lightboxIndex]}
                  alt={`Viewer ${lightboxIndex + 1}`}
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full bg-[#1e4535] flex items-center justify-center text-white/50 text-[14px]">
                  Hình ảnh sân
                </div>
              )}
            </div>

            <div className="mt-3 text-center">
              <p className="text-[12px] font-medium text-white/70">
                Ảnh {lightboxIndex + 1} / {displayImages.length}
              </p>
            </div>

            <div className="flex gap-4 justify-center mt-4">
              <button
                onClick={() => setLightboxIndex(prev => (prev > 0 ? prev - 1 : displayImages.length - 1))}
                className="flex items-center gap-1.5 bg-white/10 hover:bg-white/20 active:bg-white/30 text-white text-[13px] font-medium px-4 py-2 rounded-[8px] transition-all border border-white/10"
                type="button"
              >
                <IconArrowLeft className="w-4 h-4" />
                <span>Trước</span>
              </button>
              <button
                onClick={() => setLightboxIndex(prev => (prev < displayImages.length - 1 ? prev + 1 : 0))}
                className="flex items-center gap-1.5 bg-white/10 hover:bg-white/20 active:bg-white/30 text-white text-[13px] font-medium px-4 py-2 rounded-[8px] transition-all border border-white/10"
                type="button"
              >
                <span>Sau</span>
                <IconArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  )
}
