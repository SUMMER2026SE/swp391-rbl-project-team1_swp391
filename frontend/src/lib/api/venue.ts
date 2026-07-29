import api from '../api'

export interface TimeSlotItem {
  slotId: number
  startTime: string
  endTime: string
  slotStatus: 'AVAILABLE' | 'BOOKED' | 'MAINTENANCE'
}

export interface ReviewDto {
  reviewId: number
  userId: number
  userName: string
  userAvatar: string | null
  ratingScore: number
  comment: string
  ownerResponse: string | null
  createdAt: string
}

export interface PageResponse<T> {
  content: T[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
  last: boolean
}

export interface VenueDetail {
  stadiumId: number
  stadiumName: string
  description: string
  address: string
  pricePerHour: number
  averageRating: number
  totalReviews: number
  latitude?: number
  longitude?: number
  sportName: string
  imageUrls: string[]
  footballFieldType?: 'FIVE_A_SIDE' | 'SEVEN_A_SIDE' | 'ELEVEN_A_SIDE' | 'FUTSAL' | null
  openTime: string
  closeTime: string
  stadiumStatus: string
  complexId?: number
  complexName?: string
  parentStadiumId?: number
  facilityName?: string
  nodeType?: string
  amenities: Array<{
    amenityId: number
    name: string
    icon: string
  }>
  accessories: Array<{
    accessoryId: number
    name: string
    pricePerUnit: number
    quantity: number
  }>
  timeSlots: TimeSlotItem[]
  owner: {
    ownerId: number
    ownerUserId: number
    ownerName: string
    phoneNumber: string
  }
  recentReviews: ReviewDto[]
}

export async function getVenueDetail(id: number): Promise<VenueDetail> {
  const res = await api.get<VenueDetail>(`/public/stadiums/${id}`)
  return res.data
}

export async function getVenueReviews(
  id: number,
  page: number = 0,
  size: number = 5
): Promise<PageResponse<ReviewDto>> {
  const res = await api.get<PageResponse<ReviewDto>>(
    `/public/stadiums/${id}/reviews`,
    { params: { page, size } }
  )
  return res.data
}
