/**
 * /venues/[id] — Render VenueDetail for courts (Server Component).
 */
import { notFound } from 'next/navigation'
import { getVenueDetail } from '@/lib/api/venue'
import VenueDetail from '@/components/venues/VenueDetail'
import { Header } from '@/components/layout/Header'

interface PageProps {
  params: { id: string }
  searchParams: Record<string, string | string[] | undefined>
}

export default async function VenueDetailPage({ params }: PageProps) {
  const venueId = parseInt(params.id, 10)

  if (isNaN(venueId)) {
    notFound()
  }

  // --- Render VenueDetail for courts ---
  let venue
  try {
    venue = await getVenueDetail(venueId)
  } catch {
    notFound()
  }

  if (!venue) notFound()

  const formatTimeNum = (timeStr: string) => {
    try {
      const parts = timeStr.split(':')
      return `${parts[0]}:${parts[1]}`
    } catch {
      return timeStr
    }
  }

  const mappedVenue = {
    id: venue.stadiumId,
    name: venue.stadiumName,
    complexId: venue.complexId,
    complexName: venue.complexName,
    facilityId: venue.parentStadiumId,
    facilityName: venue.facilityName,
    nodeType: venue.nodeType as any,
    sport: venue.sportName,
    address: venue.address,
    rating: venue.averageRating || 0,
    reviewCount: venue.totalReviews || 0,
    pricePerHour: venue.pricePerHour,
    footballFieldType: venue.footballFieldType,
    openTime: formatTimeNum(venue.openTime),
    closeTime: formatTimeNum(venue.closeTime),
    status: venue.stadiumStatus,
    description: venue.description,
    images: venue.imageUrls || [],
    amenities: venue.amenities?.map((a) => a.name) || [],
    timeSlots: venue.timeSlots?.map((s) => {
      const parts = s.startTime.split('T')
      const date = parts[0]
      const hour = parts[1] ? parts[1].substring(0, 5) : '08:00'
      return { date, hour, available: s.slotStatus === 'AVAILABLE' }
    }) || [],
    services: venue.accessories?.map((a) => ({
      name: a.name,
      stock: a.quantity,
      price: a.pricePerUnit,
    })) || [],
    owner: {
      userId: venue.owner?.ownerUserId,
      name: venue.owner?.ownerName || 'N/A',
      initials: venue.owner?.ownerName
        ? venue.owner.ownerName.split(' ').slice(-2).map((w: string) => w[0]).join('')
        : '??',
      phone: venue.owner?.phoneNumber || 'N/A',
    },
    coordinates: {
      lat: venue.latitude || 0,
      lng: venue.longitude || 0,
    },
    recentReviews: venue.recentReviews?.map((r) => ({
      reviewId: r.reviewId,
      userId: r.userId,
      userName: r.userName,
      userAvatar: r.userAvatar ?? null,
      ratingScore: r.ratingScore,
      comment: r.comment,
      ownerResponse: r.ownerResponse ?? null,
      createdAt: r.createdAt,
    })) || [],
  }

  return (
    <div className="min-h-screen bg-white">
      <Header />
      <VenueDetail venue={mappedVenue} />
    </div>
  )
}
