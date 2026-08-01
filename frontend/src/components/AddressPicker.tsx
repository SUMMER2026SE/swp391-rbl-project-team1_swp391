'use client'

import { useState, useCallback, useEffect, useRef } from 'react'
import dynamic from 'next/dynamic'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card } from '@/components/ui/card'
import { MapPin } from 'lucide-react'
import 'leaflet/dist/leaflet.css'
import { get } from '@/lib/api'

const DA_NANG_CENTER = { lat: 16.0544, lng: 108.2022 }

interface AddressData {
  addressText: string
  lat: number
  lng: number
}

interface Props {
  initialAddress?: string
  initialLat?: number
  initialLng?: number
  onAddressChange: (data: AddressData) => void
}

interface LocationDTO {
  displayName: string
  latitude: number
  longitude: number
  province?: string
  district?: string
  ward?: string
  street?: string
  source?: string
}

const LeafletMap = dynamic(
  () => import('./LeafletMap'),
  {
    ssr: false,
    loading: () => <div className="h-full w-full flex items-center justify-center bg-muted">Đang tải bản đồ...</div>
  }
)

export function AddressPicker({ initialAddress, initialLat, initialLng, onAddressChange }: Props) {
  const [position, setPosition] = useState<{ lat: number; lng: number }>(() => {
    if (initialLat !== undefined && initialLat !== null && initialLng !== undefined && initialLng !== null) {
      return { lat: initialLat, lng: initialLng }
    }
    return DA_NANG_CENTER
  })
  const [inputValue, setInputValue] = useState(initialAddress || '')
  const [suggestions, setSuggestions] = useState<LocationDTO[]>([])
  const [showDropdown, setShowDropdown] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [isFocused, setIsFocused] = useState(false)
  const debounceTimerRef = useRef<NodeJS.Timeout>()
  const wrapperRef = useRef<HTMLDivElement>(null)
  const [isClient, setIsClient] = useState(false)

  // Memory cache
  const cacheRef = useRef<Map<string, LocationDTO[]>>(new Map())
  const reverseCacheRef = useRef<Map<string, LocationDTO>>(new Map())

  // Keep local input value in sync with prop from react-hook-form parent only when not typing
  useEffect(() => {
    if (!isFocused && initialAddress !== undefined && initialAddress !== null && initialAddress !== inputValue) {
      setInputValue(initialAddress)
    }
  }, [initialAddress, inputValue, isFocused])

  // Keep local map position in sync with prop from react-hook-form parent
  useEffect(() => {
    if (initialLat !== undefined && initialLat !== null && initialLng !== undefined && initialLng !== null && (initialLat !== position.lat || initialLng !== position.lng)) {
      setPosition({ lat: initialLat, lng: initialLng })
    }
  }, [initialLat, initialLng, position])

  useEffect(() => {
    setIsClient(true)
    if (typeof window !== 'undefined') {
      const L = require('leaflet')
      delete (L.Icon.Default.prototype as any)._getIconUrl
      L.Icon.Default.mergeOptions({
        iconRetinaUrl: '/leaflet/marker-icon-2x.png',
        iconUrl: '/leaflet/marker-icon.png',
        shadowUrl: '/leaflet/marker-shadow.png',
      })
    }
  }, [])

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target as Node)) {
        setShowDropdown(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const reverseGeocode = useCallback(
    async (lat: number, lng: number) => {
      const roundedLat = Number(lat.toFixed(8))
      const roundedLng = Number(lng.toFixed(8))
      const cacheKey = `${roundedLat},${roundedLng}`
      if (reverseCacheRef.current.has(cacheKey)) {
        const cached = reverseCacheRef.current.get(cacheKey)!
        setInputValue(cached.displayName)
        onAddressChange({ addressText: cached.displayName, lat: roundedLat, lng: roundedLng })
        return
      }

      try {
        const data = await get<LocationDTO>(`/geocoding/reverse?lat=${roundedLat}&lng=${roundedLng}`)
        
        if (data && data.displayName) {
          reverseCacheRef.current.set(cacheKey, data)
          setInputValue(data.displayName)
          onAddressChange({ addressText: data.displayName, lat: roundedLat, lng: roundedLng })
        } else {
          setInputValue('')
          onAddressChange({ addressText: '', lat: roundedLat, lng: roundedLng })
        }
      } catch (err) {
        console.error('Reverse geocode error:', err)
        setInputValue('')
        onAddressChange({ addressText: '', lat: roundedLat, lng: roundedLng })
      }
    },
    [onAddressChange]
  )

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value
    setInputValue(value)

    // Cập nhật địa chỉ về form cha khi người dùng gõ tay
    onAddressChange({ addressText: value, lat: position.lat, lng: position.lng })

    if (value.trim().length < 3) {
      setSuggestions([])
      setShowDropdown(false)
      setIsLoading(false)
      return
    }

    if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current)

    debounceTimerRef.current = setTimeout(async () => {
      const q = value.trim()
      if (cacheRef.current.has(q)) {
        setSuggestions(cacheRef.current.get(q)!)
        setShowDropdown(true)
        return
      }

      setIsLoading(true)
      try {
        const data = await get<LocationDTO[]>(`/geocoding/search?q=${encodeURIComponent(q)}`)
        cacheRef.current.set(q, data)
        setSuggestions(data)
        setShowDropdown(true)
      } catch (err) {
        console.error('Autocomplete error:', err)
        setSuggestions([])
        setShowDropdown(true)
      } finally {
        setIsLoading(false)
      }
    }, 500)
  }, [onAddressChange, position])

  const selectSuggestion = useCallback(
    (item: LocationDTO) => {
      const lat = Number(item.latitude.toFixed(8))
      const lng = Number(item.longitude.toFixed(8))
      setPosition({ lat, lng })
      setInputValue(item.displayName)
      setShowDropdown(false)
      onAddressChange({ addressText: item.displayName, lat, lng })
    },
    [onAddressChange]
  )

  const handleMarkerDragEnd = useCallback(
    (lat: number, lng: number) => {
      const roundedLat = Number(lat.toFixed(8))
      const roundedLng = Number(lng.toFixed(8))
      setPosition({ lat: roundedLat, lng: roundedLng })
      setInputValue('Đang tải địa chỉ...')
      reverseGeocode(roundedLat, roundedLng)
    },
    [reverseGeocode]
  )

  const handleMapClick = useCallback(
    (lat: number, lng: number) => {
      const roundedLat = Number(lat.toFixed(8))
      const roundedLng = Number(lng.toFixed(8))
      setPosition({ lat: roundedLat, lng: roundedLng })
      setInputValue('Đang tải địa chỉ...')
      reverseGeocode(roundedLat, roundedLng)
    },
    [reverseGeocode]
  )

  if (!isClient) {
    return (
      <div className="space-y-4">
        <div className="relative">
          <Label htmlFor="address-input">Địa chỉ sân *</Label>
          <div className="relative mt-2">
            <MapPin className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
            <Input id="address-input" placeholder="Đang tải bản đồ..." className="pl-10" disabled />
          </div>
        </div>
        <div className="h-[400px] rounded-lg overflow-hidden border bg-muted flex items-center justify-center">
          Đang tải bản đồ...
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="relative z-[9999]" ref={wrapperRef}>
        <Label htmlFor="address-input">Địa chỉ sân *</Label>
        <div className="relative mt-2">
          <MapPin className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
          <Input
            id="address-input"
            value={inputValue}
            onChange={handleInputChange}
            onFocus={() => {
              setIsFocused(true)
              if (inputValue.length >= 3) setShowDropdown(true)
            }}
            onBlur={() => {
              setIsFocused(false)
            }}
            placeholder={inputValue === '' ? 'Nhập tay địa chỉ (Không tìm thấy tự động)' : 'Nhập địa chỉ (VD: 45 Lê Lợi, Quận 1...)'}
            className="pl-10"
            autoComplete="off"
          />
          {isLoading && (
            <div className="absolute right-3 top-3 text-xs text-muted-foreground">
              Đang tìm...
            </div>
          )}
        </div>
        {showDropdown && (
          <Card className="absolute z-50 w-full mt-1 max-h-60 overflow-y-auto p-1 space-y-1">
            <button
              type="button"
              onClick={() => {
                setShowDropdown(false)
                onAddressChange({ addressText: inputValue, lat: position.lat, lng: position.lng })
              }}
              className="w-full text-left px-4 py-2.5 hover:bg-muted text-primary transition-colors text-sm font-semibold border-b last:border-b-0 flex items-center gap-2"
            >
              <span className="text-muted-foreground font-normal text-xs">Sử dụng địa chỉ đã gõ:</span> "{inputValue}"
            </button>
            {suggestions.length > 0 ? (
              suggestions.map((item, idx) => (
                <button
                  key={idx}
                  type="button"
                  onClick={() => selectSuggestion(item)}
                  className="w-full text-left px-4 py-3 hover:bg-muted transition-colors text-sm border-b last:border-b-0 rounded-sm"
                >
                  {item.displayName}
                </button>
              ))
            ) : (
              <div className="px-4 py-3 text-xs text-muted-foreground italic">
                Không tìm thấy gợi ý tự động. Bạn vẫn có thể nhấp vào nút "Sử dụng địa chỉ đã gõ" ở trên.
              </div>
            )}
          </Card>
        )}
      </div>
      <p className="text-sm text-muted-foreground mt-1">Hoặc ghim vị trí trực tiếp trên bản đồ bên dưới</p>

      <div className="h-[400px] rounded-lg overflow-hidden border mt-2">
        <LeafletMap position={position} onMapClick={handleMapClick} onDragEnd={handleMarkerDragEnd} />
      </div>
    </div>
  )
}
